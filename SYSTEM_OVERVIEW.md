# Appointment Notifier — System Overview

## Architecture overview

The module is an OpenMRS OMOD that bridges Bahmni appointment events to an external SaaS SMS
endpoint. The core pattern is a **Transactional Outbox**: events are never POSTed directly in the
save call. Instead they are written to a local database table first, and a scheduled task picks
them up for delivery. This gives guaranteed delivery with automatic retry, even if the remote
endpoint is temporarily down.

```
Clinician creates / updates / cancels Appointment (Bahmni)
        │
        ▼
AppointmentsService.validateAndSave() / changeStatus() / ...   ← AOP intercept
        │
        ▼
AppointmentServiceAdvice.invoke()
   ├── build flat JSON payload (appointment + patient fields)
   └── OutboxServiceImpl.enqueue()  ── INSERT → saas_integration_queue
                                                (status = PENDING)

Every 5 minutes:
SaasQueueTask.execute()
   ├── OutboxServiceImpl.findDispatchable()  ── SELECT PENDING/FAILED
   ├── SaasHttpClient.sendPayload()          ── HTTP POST
   └── OutboxServiceImpl.markSent / markFailed
```

---

## 1. Module startup — `AppointmentNotifierActivator`

OpenMRS calls `started()` when the module loads.

```java
@Override
public void started() {
    registerBahmniAdvice();    // 1. hook into Bahmni AppointmentsService
    registerSchedulerTask();   // 2. register the 5-minute dispatcher
}
```

**Registering the appointment hook:**
```java
private void registerBahmniAdvice() {
    bahmniAdvicedClass = Context.loadClass(
        "org.openmrs.module.appointments.service.AppointmentsService");
    Context.addAdvice(bahmniAdvicedClass, new AppointmentServiceAdvice());
}
```
The Bahmni appointments module is declared `aware_of_module` (optional). If it is not installed,
`ClassNotFoundException` is caught and the advice is silently skipped.

**Registering the scheduler task:**
```java
TaskDefinition taskDef = new TaskDefinition();
taskDef.setName("SaaS Queue Dispatcher");
taskDef.setTaskClass(SaasQueueTask.class.getName());
taskDef.setRepeatInterval(300L);   // 300 seconds = 5 minutes
taskDef.setStartOnStartup(true);
scheduler.scheduleTask(taskDef);
```
OpenMRS persists `TaskDefinition` in its own table, so on subsequent server restarts it reuses the
existing definition rather than creating a duplicate.

---

## 2. Capturing appointment events — `AppointmentServiceAdvice`

Implements `MethodInterceptor` (Spring AOP / AOP Alliance). Intercepts five methods on
`AppointmentsService`:

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

After `invocation.proceed()` (the actual save), the advice reads the appointment status to
classify the event:

```java
private String classifyEvent(Object appt) {
    String status = apptStatus(appt);
    if ("Cancelled".equalsIgnoreCase(status)) return "CANCELLED";
    return "SCHEDULED";
}
```

It then builds a flat JSON payload and enqueues it in the outbox — all in the same thread and
transaction as the appointment save:

```java
Object patient = extractPatient(appointment);
String payload = buildJsonPayload(appointment, patient, eventType);

OutboxService outboxService = Context.getRegisteredComponent(
    "appointmentNotifierOutboxService", OutboxService.class);
outboxService.enqueue(uuid, payload);
```

**Payload structure:**
```json
{
  "event":           "SCHEDULED",
  "appointmentUuid": "3fa85f64-...",
  "patientUuid":     "7d3a1c00-...",
  "patientName":     "Jan Janssen",
  "artsName":        "Dr. A. de Vries",
  "status":          "Scheduled",
  "phoneNumber":     "+31612345678",
  "service":         "General Consultation",
  "location":        "OPD Clinic",
  "startDateTime":   "2026-05-20T09:00:00Z",
  "endDateTime":     "2026-05-20T09:30:00Z",
  "comments":        "Follow-up visit"
}
```

All Bahmni classes are accessed via reflection — no compile-time dependency on Bahmni JARs. This
keeps the module deployable on OpenMRS installations where Bahmni is absent.

---

## 3. The outbox table

Liquibase creates the table on first module load:

```xml
<createTable tableName="saas_integration_queue">
    <column name="id"             type="INT" autoIncrement="true"/>
    <column name="encounter_uuid" type="VARCHAR(38)" nullable="false"/>  <!-- stores appointment UUID -->
    <column name="fhir_payload"   type="MEDIUMTEXT"/>                    <!-- stores appointment JSON -->
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
FAILED   ──(retry ok)───►  SENT
FAILED   ──(maxRetries)──►  stays FAILED, skipped forever
```

To manually requeue a permanently-failed entry:
```sql
UPDATE saas_integration_queue
SET status = 'PENDING', retry_count = 0
WHERE id = <id>;
```

Useful queries:
```sql
-- View recent entries
SELECT id, encounter_uuid AS appointment_uuid, status, retry_count, created_at
FROM saas_integration_queue
ORDER BY id DESC LIMIT 20;

-- View the full payload of an entry
SELECT fhir_payload FROM saas_integration_queue WHERE id = <id>;
```

---

## 4. Outbox operations — `OutboxServiceImpl`

Uses Hibernate native SQL against the `SessionFactory` that OpenMRS already manages.

```java
@Service("appointmentNotifierOutboxService")
public class OutboxServiceImpl implements OutboxService {

    @Autowired
    private SessionFactory sessionFactory;

    @Override
    @Transactional
    public void enqueue(String appointmentUuid, String payload) {
        sessionFactory.getCurrentSession()
            .createNativeQuery(
                "INSERT INTO saas_integration_queue " +
                "(encounter_uuid, fhir_payload, status, retry_count, created_at, updated_at) " +
                "VALUES (:encounterUuid, :fhirPayload, 'PENDING', 0, NOW(), NOW())")
            .setParameter("encounterUuid", appointmentUuid)
            .setParameter("fhirPayload",   payload)
            .executeUpdate();
    }
}
```

Because `enqueue()` is `@Transactional`, the INSERT joins the appointment save transaction. If the
appointment save is rolled back, the outbox INSERT is rolled back too — no orphaned entries.

---

## 5. The dispatcher — `SaasQueueTask`

OpenMRS's scheduler calls `execute()` every 5 minutes.

```java
public void execute() {
    if (isExecuting) { return; }   // prevents overlapping runs
    isExecuting = true;
    Context.openSession();
    try {
        runDispatchCycle();
    } finally {
        Context.closeSession();
        isExecuting = false;
    }
}
```

`runDispatchCycle()` fetches up to 100 `PENDING`/`FAILED` entries and dispatches each one:

```java
for (OutboxEntry entry : outbox.findDispatchable(maxRetries)) {
    boolean success = httpClient.sendPayload(
        endpoint, provider, entry.getEncounterUuid(), entry.getFhirPayload());

    if (success) outbox.markSent(entry.getId());
    else         outbox.markFailed(entry.getId());   // retry_count++
}
```

---

## 6. HTTP delivery — `SaasHttpClient`

```java
public boolean sendPayload(String endpoint, String provider,
                           String appointmentUuid, String payload) {
    // POST the payload JSON directly
    return doPost(endpoint, payload, provider, appointmentUuid);
}
```

Returns `true` for HTTP 2xx, `false` for anything else (4xx, 5xx, timeout, connection error).

Custom headers sent to the SaaS:
```
Content-Type: application/json; charset=UTF-8
X-Messaging-Provider: SwiftSend   (configurable)
X-Source: appointmentnotifier-omod
```

---

## 7. Configuration — Global Properties

All configuration lives in OpenMRS Global Properties, editable in the admin UI without restarting.
Values are read at dispatch time, so changes take effect at the next scheduler cycle.

| Property key | Default | Purpose |
|---|---|---|
| `appointmentnotifier.saasEndpoint` | `http://host.docker.internal:8888/...` | Where to POST the appointment payload |
| `appointmentnotifier.messagingProvider` | `Generic` | Value of `X-Messaging-Provider` header |
| `appointmentnotifier.maxRetries` | `5` | Failures before an entry is abandoned |
| `appointmentnotifier.enabled` | `true` | Master kill-switch |
| `appointmentnotifier.openmrsBaseUrl` | `http://localhost:8080/openmrs` | Used by the REST resource |
| `appointmentnotifier.fhirUsername` | `admin` | Credentials for internal REST calls |
| `appointmentnotifier.fhirPassword` | `Admin123` | Credentials for internal REST calls |

---

## 8. Data flow end to end

```
1. A clinician saves, reschedules, or cancels an appointment in Bahmni.

2. AppointmentServiceAdvice.invoke() fires (AOP, same thread + transaction).
   - Calls invocation.proceed() → appointment saved to DB.
   - Reads appointment status → classifies as SCHEDULED or CANCELLED.
   - Builds flat JSON payload with all required fields.
   - OutboxServiceImpl.enqueue()
     → INSERT INTO saas_integration_queue (status='PENDING', retry_count=0)
     → committed in the same transaction as the appointment save.

3. Every 5 minutes, SaasQueueTask fires:
   - SELECT ... WHERE status IN ('PENDING','FAILED') AND retry_count < 5
   - For each row:
       SaasHttpClient.sendPayload(endpoint, provider, uuid, payload)
         → HTTP POST with appointment JSON
         → 2xx  : markSent  (status='SENT')
         → error: markFailed (status='FAILED', retry_count++)
   - After 5 failures: row stays FAILED, dispatcher skips it.
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
  AppointmentServiceAdvice.java       — AOP hook on Bahmni AppointmentsService → outbox enqueue
  api/
    AppointmentInfo.java              — POJO used by the REST resource
  outbox/
    OutboxEntry.java                  — POJO (one DB row)
    OutboxService.java                — interface
    OutboxServiceImpl.java            — Hibernate native SQL impl
  task/
    SaasQueueTask.java                — 5-min scheduled dispatcher
  client/
    SaasHttpClient.java               — raw HTTP POST to SaaS endpoint
omod/
  web/rest/AppointmentNotifierResource.java   — GET /ws/rest/v1/appointmentnotifier
resources/
  liquibase.xml                       — creates saas_integration_queue
  config.xml                          — Global Properties, module metadata
```
