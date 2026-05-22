# Appointment Notifier

An OpenMRS module that automatically sends SMS notifications when appointments are created, rescheduled, or cancelled in Bahmni. It hooks into the Bahmni appointments service and forwards appointment events to an external SaaS SMS provider.

## How it works

When a clinician saves or changes an appointment, the module writes the event to a local database table (`saas_integration_queue`). A scheduled task runs every 5 minutes, picks up pending entries, and POSTs them to the configured SaaS endpoint. Failed deliveries are retried up to 5 times before being abandoned.

```
Appointment saved in Bahmni
        ↓
AOP intercept → INSERT into saas_integration_queue (PENDING)
        ↓
Every 5 min: SaasQueueTask
        ↓
HTTP POST → SaaS SMS endpoint
```

## Requirements

- Java 8+
- Maven 3.x
- OpenMRS 2.5.0+
- Bahmni Appointments module (optional — the module loads without it but won't capture events)

## Build

```bash
mvn clean package
```

The deployable module file is produced at:

```
omod/target/appointmentnotifier-<version>.omod
```

## Deploy

1. Copy the `.omod` file to the OpenMRS modules directory (e.g. `~/.OpenMRS/modules/`).
2. Restart OpenMRS, or install it via **Administration → Manage Modules → Add or Upgrade Module**.

## Configuration

All settings are OpenMRS Global Properties. Edit them at **Administration → Advanced Settings**.

| Property | Default | Description |
|---|---|---|
| `appointmentnotifier.saasEndpoint` | `http://host.docker.internal:8888/...` | URL to POST appointment events to |
| `appointmentnotifier.saasWebhookToken` | _(blank)_ | Bearer token for the SaaS endpoint |
| `appointmentnotifier.enabled` | `true` | Master on/off switch |
| `appointmentnotifier.maxRetries` | `5` | Max delivery attempts before giving up |
| `appointmentnotifier.messagingProvider` | `SwiftSend` | SMS provider: `SwiftSend`, `AsyncFlow`, `LegacyLink`, `SecurePost` |
| `appointmentnotifier.messagingProviderToken` | _(blank)_ | API key (SwiftSend / AsyncFlow) |
| `appointmentnotifier.messagingProviderUsername` | _(blank)_ | Username (LegacyLink) |
| `appointmentnotifier.messagingProviderPassword` | _(blank)_ | Password (LegacyLink) |
| `appointmentnotifier.messagingProviderClientId` | _(blank)_ | Client ID (SecurePost) |
| `appointmentnotifier.messagingProviderClientSecret` | _(blank)_ | Client secret (SecurePost) |
| `appointmentnotifier.hospitalName` | _(blank)_ | Facility name included in payloads |
| `appointmentnotifier.openmrsBaseUrl` | `http://localhost:8080/openmrs` | OpenMRS base URL for internal REST calls |
| `appointmentnotifier.openmrsUsername` | `admin` | Username for internal REST calls |
| `appointmentnotifier.openmrsPassword` | `Admin123` | Password for internal REST calls |

Changes to global properties take effect at the next scheduler cycle — no restart required.

## Troubleshooting

View the outbox queue directly in the database:

```sql
-- Recent entries and their status
SELECT id, encounter_uuid AS appointment_uuid, status, retry_count, created_at
FROM saas_integration_queue
ORDER BY id DESC LIMIT 20;

-- Requeue a permanently failed entry
UPDATE saas_integration_queue
SET status = 'PENDING', retry_count = 0
WHERE id = <id>;
```
