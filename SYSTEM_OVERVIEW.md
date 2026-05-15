# Appointment Notifier — System Overview

## Architecture overview

The module is an OpenMRS OMOD that bridges patient encounter events to an external SaaS endpoint.
The core pattern is a **Transactional Outbox**: events are never POSTed directly in the save call.
Instead they are written to a local database table first, and a scheduled task picks them up for
delivery. This gives you guaranteed delivery with automatic retry, even if the remote endpoint is
temporarily down.

```
Clinician saves Encounter
        │
        ▼
EncounterService.saveEncounter()   ← AOP intercept
        │
        ▼
EncounterEventListener (async thread)
   ├── reload Encounter from DB
   ├── FhirEncounterSerializer.toFhirJson()
   └── OutboxServiceImpl.enqueue()   ── INSERT → saas_integration_queue
                                                 (status = PENDING)

Every 5 minutes:
SaasQueueTask.execute()
   ├── OutboxServiceImpl.findDispatchable()  ── SELECT PENDING/FAILED
   ├── SaasHttpClient.sendPayload()          ── HTTP POST
   └── OutboxServiceImpl.markSent / markFailed
```

---

## 1. Module startup — `AppointmentNotifierActivator`

OpenMRS calls `started()` when the module loads. The activator does three things.

```java
@Override
public void started() {
    registerEncounterAdvice();   // 1. hook into EncounterService
    registerSchedulerTask();     // 2. register the 5-minute dispatcher
    registerBahmniAdvice();      // 3. optional — only if Bahmni module is installed
}
```

**Registering the encounter hook:**
```java
private void registerEncounterAdvice() {
    encounterAdvice = new EncounterEventListener();
    Context.addAdvice(EncounterService.class, encounterAdvice);
}
```
`Context.addAdvice()` is OpenMRS's way of attaching Spring AOP interceptors to core services at
runtime. From this point on, every call to any method on `EncounterService` passes through
`EncounterEventListener.invoke()` before and after execution.

**Registering the scheduler task:**
```java
TaskDefinition taskDef = new TaskDefinition();
taskDef.setName("SaaS Queue Dispatcher");
taskDef.setTaskClass(SaasQueueTask.class.getName());
taskDef.setRepeatInterval(300L);   // 300 seconds = 5 minutes
taskDef.setStartOnStartup(true);
scheduler.scheduleTask(taskDef);
```
OpenMRS persists `TaskDefinition` in its own database table, so on subsequent server restarts it
reuses the existing definition rather than creating a duplicate.

**Registering Bahmni advice:**
```java
private void registerBahmniAdvice() {
    try {
        bahmniAdvicedClass = Context.loadClass(
            "org.openmrs.module.appointments.service.AppointmentsService");
        Context.addAdvice(bahmniAdvicedClass, new AppointmentServiceAdvice());
    } catch (ClassNotFoundException e) {
        log.info("Bahmni appointments module absent — Bahmni advice skipped.");
    }
}
```
The Bahmni appointments module is declared `aware_of_module` (optional), not `require_module`.
The activator tries to load its service class and silently skips if it is not installed.

---

## 2. Capturing encounter saves — `EncounterEventListener`

This class implements `MethodInterceptor` (Spring AOP / AOP Alliance). Its `invoke()` wraps every
method call on `EncounterService`.

```java
@Override
public Object invoke(MethodInvocation invocation) throws Throwable {
    String methodName = invocation.getMethod().getName();

    // Only care about save methods
    if (!isSaveMethod(methodName)) {
        return invocation.proceed();          // pass-through for everything else
    }

    // Honour the global kill-switch
    if (!isEnabled()) {
        return invocation.proceed();
    }

    Object result = invocation.proceed();     // let the actual save happen first

    Encounter encounter = resolveEncounter(result, invocation.getArguments());
    if (encounter == null || encounter.isVoided()) {
        return result;
    }

    final String uuid = encounter.getUuid();
    executor.submit(() -> enqueueInOwnSession(uuid));   // fire-and-forget

    return result;
}

private static boolean isSaveMethod(String name) {
    return "saveEncounter".equals(name) || "saveEncounterWithObservations".equals(name);
}
```

Key design choices:
- `invocation.proceed()` is called **before** any outbox work. The encounter must exist in the
  database before the async thread tries to reload it.
- Only the UUID string is captured and passed to the async thread. The `Encounter` object itself
  is a Hibernate entity belonging to the main thread's session — passing it across threads would
  cause `LazyInitializationException` on any lazy-loaded collection.
- The executor has 2 threads. Enqueueing is fast (one INSERT) so the queue never backs up under
  normal load.

**The async thread:**
```java
private void enqueueInOwnSession(String uuid) {
    Context.openSession();
    try {
        Context.addProxyPrivilege("Get Encounters");

        Encounter encounter = Context.getEncounterService().getEncounterByUuid(uuid);
        if (encounter == null || encounter.isVoided()) return;

        FhirEncounterSerializer serializer = Context.getRegisteredComponent(
            "fhirEncounterSerializer", FhirEncounterSerializer.class);
        OutboxService outboxService = Context.getRegisteredComponent(
            "appointmentNotifierOutboxService", OutboxService.class);

        String fhirPayload = serializer.toFhirJson(encounter);
        outboxService.enqueue(uuid, fhirPayload);
    } catch (Exception e) {
        log.error("failed to enqueue encounter " + uuid, e);
    } finally {
        Context.removeProxyPrivilege("Get Encounters");
        Context.closeSession();
    }
}
```
`Context.openSession()` establishes an OpenMRS user context on this thread (needed for privilege
checks inside `EncounterService.getEncounterByUuid`). `Context.addProxyPrivilege()` grants the
`Get Encounters` privilege to the anonymous thread so the load succeeds without a logged-in user.

---

## 3. FHIR serialization — `FhirEncounterSerializer`

The encounter must be serialized to JSON before storing. The module uses a two-tier strategy
because the fhir2 module is optional.

**Tier 1 — fhir2 via reflection:**
```java
private String tryFhirConversionViaReflection(Encounter encounter) throws Exception {
    ClassLoader cl = Thread.currentThread().getContextClassLoader();

    // Will throw ClassNotFoundException if fhir2 is not installed
    Class<?> translatorInterface = cl.loadClass(
        "org.openmrs.module.fhir2.api.translators.EncounterTranslator");

    // Get the Spring bean from the shared context
    ApplicationContext appCtx = ServiceContext.getInstance().getApplicationContext();
    Object translator = appCtx.getBeansOfType((Class) translatorInterface)
                               .values().iterator().next();

    // translator.toFhirResource(encounter) → org.hl7.fhir.r4.model.Encounter
    Method toFhirResource = findMethod(translator.getClass(), "toFhirResource");
    Object fhirEncounter  = toFhirResource.invoke(translator, encounter);

    // FhirContext.forR4().newJsonParser().encodeResourceToString(fhirEncounter)
    Object ctx    = getOrCreateFhirContext(cl);
    Object parser = ctx.getClass().getMethod("newJsonParser").invoke(ctx);
    Method encode = findMethod(parser.getClass(), "encodeResourceToString");
    return (String) encode.invoke(parser, fhirEncounter);
}
```
There are no compile-time imports of fhir2 or HAPI FHIR anywhere in the project. Every class is
resolved with `Class.forName()` / `ClassLoader.loadClass()` at runtime. This is why `api/pom.xml`
intentionally has no fhir2 dependency — if it were there, the build would fail because those
artifacts are not in any public Maven repository this module can reach.

**Tier 2 — hand-built fallback JSON:**

When fhir2 is absent the serializer builds a FHIR-shaped JSON by hand. It includes a non-standard
`_patientContact` block so the SaaS backend and `DiscordPayloadBuilder` can extract phone/email
without a second REST call:

```json
{
  "resourceType": "Encounter",
  "id": "<uuid>",
  "status": "unknown",
  "subject": { "reference": "Patient/<uuid>", "display": "Jan Janssen" },
  "period":  { "start": "2026-05-15T09:30:00Z" },
  "location": [{ "location": { "display": "OPD Clinic" } }],
  "_patientContact": { "phone": "+31612345678", "email": null }
}
```

The fallback JSON is intentionally FHIR-shaped (same field names) so the SaaS consumer does not
need to distinguish between the two paths.

---

## 4. The outbox table

Liquibase creates the table on first module load:

```xml
<createTable tableName="saas_integration_queue">
    <column name="id"             type="INT" autoIncrement="true"/>
    <column name="encounter_uuid" type="VARCHAR(38)" nullable="false"/>
    <column name="fhir_payload"   type="MEDIUMTEXT"/>   <!-- up to 16 MB -->
    <column name="status"         type="VARCHAR(10)" defaultValue="PENDING"/>
    <column name="retry_count"    type="INT"         defaultValueNumeric="0"/>
    <column name="created_at"     type="DATETIME"/>
    <column name="updated_at"     type="DATETIME"/>
</createTable>
```

The status lifecycle:
```
PENDING  ──(HTTP 2xx)──►  SENT
PENDING  ──(error)──────►  FAILED  retry_count++
FAILED   ──(retry)──────►  SENT    (if retry_count < maxRetries)
FAILED   ──(maxRetries)──►  stays FAILED, skipped forever
```

To manually requeue a permanently-failed entry:
```sql
UPDATE saas_integration_queue
SET status = 'PENDING', retry_count = 0
WHERE id = <id>;
```

---

## 5. Outbox operations — `OutboxServiceImpl`

Uses Hibernate native SQL queries against the `SessionFactory` that OpenMRS already manages.
No JdbcTemplate, no separate DataSource bean needed.

```java
@Service("appointmentNotifierOutboxService")
public class OutboxServiceImpl implements OutboxService {

    @Autowired
    private SessionFactory sessionFactory;   // always available in OpenMRS modules

    @Override
    @Transactional
    public void enqueue(String encounterUuid, String fhirPayload) {
        sessionFactory.getCurrentSession()
            .createNativeQuery(
                "INSERT INTO saas_integration_queue " +
                "(encounter_uuid, fhir_payload, status, retry_count, created_at, updated_at) " +
                "VALUES (:encounterUuid, :fhirPayload, 'PENDING', 0, NOW(), NOW())")
            .setParameter("encounterUuid", encounterUuid)
            .setParameter("fhirPayload",   fhirPayload)
            .executeUpdate();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OutboxEntry> findDispatchable(int maxRetries) {
        List<Object[]> rows = sessionFactory.getCurrentSession()
            .createNativeQuery(
                "SELECT id, encounter_uuid, fhir_payload, status, retry_count, " +
                "created_at, updated_at " +
                "FROM saas_integration_queue " +
                "WHERE status IN ('PENDING','FAILED') AND retry_count < :maxRetries " +
                "ORDER BY id ASC LIMIT 100")
            .setParameter("maxRetries", maxRetries)
            .getResultList();

        // Each row is an Object[] — column order matches the SELECT list
        List<OutboxEntry> result = new ArrayList<>();
        for (Object[] row : rows) {
            OutboxEntry e = new OutboxEntry();
            e.setId(((Number) row[0]).intValue());
            e.setEncounterUuid((String) row[1]);
            e.setFhirPayload((String)   row[2]);
            e.setStatus((String)        row[3]);
            e.setRetryCount(((Number)   row[4]).intValue());
            if (row[5] != null) e.setCreatedAt((java.util.Date) row[5]);
            if (row[6] != null) e.setUpdatedAt((java.util.Date) row[6]);
            result.add(e);
        }
        return result;
    }
}
```

**Why `DataSource` was wrong and `SessionFactory` is right:** In OpenMRS, module Spring contexts
do not have `javax.sql.DataSource` registered as a bean. The database connection is owned by
Hibernate's `SessionFactory`, which is wired directly into the module's Spring child context.
Injecting `SessionFactory` and calling `getCurrentSession()` is the correct idiom.

---

## 6. The dispatcher — `SaasQueueTask`

OpenMRS's scheduler calls `execute()` every 5 minutes.

```java
public class SaasQueueTask extends AbstractTask {

    @Override
    public void execute() {
        if (isExecuting) {
            log.warn("previous run still in progress, skipping.");
            return;          // prevents overlapping runs
        }
        isExecuting = true;
        Context.openSession();
        try {
            Context.addProxyPrivilege("Get Encounters");
            runDispatchCycle();
        } finally {
            Context.removeProxyPrivilege("Get Encounters");
            Context.closeSession();
            isExecuting = false;
        }
    }

    private void runDispatchCycle() {
        String endpoint = Context.getAdministrationService()
            .getGlobalProperty("appointmentnotifier.saasEndpoint", "");
        if (endpoint.trim().isEmpty()) {
            log.warn("saasEndpoint not configured — skipping.");
            return;
        }

        OutboxService  outbox     = Context.getRegisteredComponent("appointmentNotifierOutboxService", ...);
        SaasHttpClient httpClient = Context.getRegisteredComponent("saasHttpClient", ...);

        List<OutboxEntry> entries = outbox.findDispatchable(maxRetries);

        for (OutboxEntry entry : entries) {
            boolean success = httpClient.sendPayload(
                endpoint, provider, entry.getEncounterUuid(), entry.getFhirPayload());

            if (success) outbox.markSent(entry.getId());
            else         outbox.markFailed(entry.getId());
        }
    }
}
```

The `isExecuting` flag comes from `AbstractTask`. If a dispatch cycle takes longer than 5 minutes
(e.g. the remote endpoint is very slow), the next scheduled firing simply skips rather than
stacking up a second concurrent run.

---

## 7. HTTP delivery — `SaasHttpClient` and `DiscordPayloadBuilder`

`SaasHttpClient` has one public method:

```java
public boolean sendPayload(String endpoint, String provider,
                           String encounterUuid, String fhirPayload) {

    boolean isDiscord = endpoint.contains("discord.com/api/webhooks");

    String body = isDiscord
        ? DiscordPayloadBuilder.build(encounterUuid, fhirPayload)
        : fhirPayload;    // raw FHIR JSON for a real SaaS endpoint

    return doPost(endpoint, body, provider, encounterUuid, isDiscord);
}
```

For non-Discord endpoints, two custom headers are added:
```
X-Messaging-Provider: SwiftSend   (or whatever is configured)
X-Source: appointmentnotifier-omod
```
These let the receiving SaaS API identify the origin and route to the right SMS/email adapter
without parsing the JSON body.

**Discord path:** `DiscordPayloadBuilder.build()` parses the stored FHIR JSON with Jackson and
produces a Discord embed:

```java
// Input: FHIR JSON with resourceType=Encounter
// Extracts: subject.display, period.start, location[0].location.display, _patientContact.phone
// Output:
{
  "embeds": [{
    "title": "📋 Encounter Event",
    "color": 3447003,
    "fields": [
      { "name": "Patient",        "value": "Jan Janssen",             "inline": true },
      { "name": "Status",         "value": "unknown",                 "inline": true },
      { "name": "Start",          "value": "15 May 2026, 09:30 UTC",  "inline": true },
      { "name": "Location",       "value": "OPD Clinic",              "inline": true },
      { "name": "Phone",          "value": "+31612345678",            "inline": true },
      { "name": "Encounter UUID", "value": "abc-123-...",             "inline": false }
    ],
    "footer": { "text": "OpenMRS Appointment Notifier — SaaS Outbox" },
    "timestamp": "2026-05-15T09:31:00Z"
  }]
}
```

It handles both the fhir2-produced JSON (standard FHIR R4) and the fallback JSON (which carries
the non-standard `_patientContact` block for phone/email).

---

## 8. Legacy Bahmni path — `AppointmentServiceAdvice`

Before the outbox was introduced, the module fired webhooks directly (fire-and-forget) for Bahmni
appointments. This path is kept for backward compatibility.

When Bahmni's `AppointmentsService` is present, `AppointmentServiceAdvice` intercepts five methods:

```java
private boolean isTrackedMethod(String name) {
    switch (name) {
        case "validateAndSave":
        case "rescheduleAppointment":
        case "changeStatus":
        case "cancelAppointment":
        case "voidAppointment":
            return true;
        default: return false;
    }
}
```

Appointments are not Encounters, so there is no FHIR step here. The advice builds a flat JSON
payload (or Discord embed) directly from the appointment object using reflection — Bahmni classes
are also optional, so the advice never imports them at compile time.

The Bahmni path POSTs directly in an executor thread and does **not** use the outbox table. It has
no retry logic. This is acceptable for a legacy path because Bahmni deployments tend to keep the
webhook target available locally.

---

## 9. Configuration — Global Properties

All configuration lives in OpenMRS Global Properties, editable in the admin UI without touching
the module. Values are read at dispatch time, not cached, so changes take effect at the next
scheduler cycle without restarting the module.

| Property key | Default | Purpose |
|---|---|---|
| `appointmentnotifier.saasEndpoint` | `http://host.docker.internal:8888/...` | Where to POST — Discord or SaaS |
| `appointmentnotifier.messagingProvider` | `Generic` | Value of `X-Messaging-Provider` header |
| `appointmentnotifier.maxRetries` | `5` | How many failures before giving up |
| `appointmentnotifier.enabled` | `true` | Master kill-switch |
| `appointmentnotifier.openmrsBaseUrl` | `http://localhost:8080/openmrs` | Used by the REST resource |
| `appointmentnotifier.fhirUsername` | `admin` | Credentials for internal API calls |
| `appointmentnotifier.fhirPassword` | `Admin123` | Credentials for internal API calls |

---

## 10. Data flow end to end

```
1. A clinician submits a form → EncounterService.saveEncounter() is called.

2. EncounterEventListener.invoke() fires (AOP, same thread).
   - Calls invocation.proceed() → encounter saved to DB.
   - Captures UUID, submits to executor.

3. Executor thread (async):
   - Context.openSession() + addProxyPrivilege("Get Encounters")
   - Reloads encounter from DB (fresh session, no detached-entity risk).
   - FhirEncounterSerializer.toFhirJson():
       a. tries fhir2 EncounterTranslator via reflection → real FHIR R4 JSON
       b. or falls back to hand-built FHIR-shaped JSON
   - OutboxServiceImpl.enqueue()
     → INSERT INTO saas_integration_queue (status='PENDING', retry_count=0)

4. Every 5 minutes, SaasQueueTask fires:
   - SELECT ... WHERE status IN ('PENDING','FAILED') AND retry_count < 5
   - For each row:
       SaasHttpClient.sendPayload(endpoint, provider, uuid, fhirPayload)
         → if Discord: DiscordPayloadBuilder converts to embed JSON
         → HTTP POST to endpoint
         → 2xx  : markSent  (status='SENT')
         → error: markFailed (status='FAILED', retry_count++)
   - After 5 failures: row stays FAILED, dispatcher skips it forever.
     Manual fix: UPDATE saas_integration_queue
                 SET status='PENDING', retry_count=0
                 WHERE id = <id>;
```

---

## Package structure

```
api/
  AppointmentNotifierActivator.java   — module lifecycle (start/stop)
  AppointmentNotifierConstants.java   — all GP keys and defaults in one place
  AppointmentServiceAdvice.java       — legacy Bahmni webhook (fire-and-forget)
  event/
    EncounterEventListener.java       — AOP hook → async enqueue
  fhir/
    FhirEncounterSerializer.java      — fhir2 via reflection + fallback JSON
  outbox/
    OutboxEntry.java                  — POJO (one DB row)
    OutboxService.java                — interface
    OutboxServiceImpl.java            — Hibernate native SQL impl
  task/
    SaasQueueTask.java                — 5-min scheduled dispatcher
  client/
    SaasHttpClient.java               — raw HTTP POST, Discord routing
    DiscordPayloadBuilder.java        — FHIR JSON → Discord embed
omod/
  web/rest/AppointmentNotifierResource.java       — REST endpoint
  web/controller/AppointmentNotifierController.java
resources/
  liquibase.xml                       — creates saas_integration_queue
  config.xml                          — Global Properties, module metadata
```
