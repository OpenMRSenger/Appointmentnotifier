# LLM/Developer Prompt: Refactor Webhook Sender to HL7 FHIR Appointment Format

You can use the prompt below to instruct your other API (or an AI assistant writing its code) to construct and send the correct FHIR-compliant payloads.

---

## Refactoring Prompt

Please update the webhook sender module to map and send appointment details according to the **HL7 FHIR Appointment Resource** standard, replacing the legacy payload format. 

### 1. Payload Mapping Rules

| Legacy Field | New FHIR Path | Formatting / Notes |
| :--- | :--- | :--- |
| `event` | *N/A (Omitted)* | Omit the top-level event field, as the resource type and status handle the intent. |
| `appointmentUuid` | `id` | Must be a valid UUID string representing the appointment ID. |
| *N/A* | `resourceType` | Must be the constant string `"Appointment"`. |
| `status` | `status` | Map the status to valid lowercase FHIR values: <br>- `"Scheduled"` / `"Active"` $\rightarrow$ `"booked"`<br>- `"Cancelled"` $\rightarrow$ `"cancelled"`<br>- Other valid values: `proposed`, `pending`, `arrived`, `fulfilled`, `noshow`, `entered-in-error`, `checked-in`, `waitlist`. |
| `startDateTime` | `start` | Datetime formatted as ISO-8601 offset string (e.g. `2026-06-24T12:00:00+02:00` or `2026-06-24T10:00:00Z`). |
| `endDateTime` | `end` | Datetime formatted as ISO-8601 offset string. |
| `patientUuid` | `participant[actor.reference]` | Stored as a reference link with a `"Patient/"` prefix (e.g., `"Patient/patient-uuid"`). |
| `patientName` | `participant[actor.display]` | Display name of the Patient participant. |
| `phoneNumber` | `participant[actor.telecom]` | Listed inside the Patient participant's actor telecom array with system `"phone"`. |
| `artsName` | `participant[actor.display]` | Display name of the Practitioner participant (reference starts with `"Practitioner/"`). |

---

### 2. Examples

#### BEFORE: Legacy Flat JSON Payload
```json
{
  "event": "APPOINTMENT_SCHEDULED",
  "appointmentUuid": "2fba677c-7a93-41e9-9069-79774e64259b",
  "patientUuid": "e7c65c08-0131-4c12-9c3f-c30f40d39e80",
  "patientName": "John Doe",
  "artsName": "Dr. Alice Smith",
  "status": "Scheduled",
  "phoneNumber": "+31612345678",
  "startDateTime": "2026-06-24T12:00:00+02:00",
  "endDateTime": "2026-06-24T12:30:00+02:00"
}
```

#### AFTER: HL7 FHIR Appointment Resource JSON Payload
```json
{
  "resourceType": "Appointment",
  "id": "2fba677c-7a93-41e9-9069-79774e64259b",
  "status": "booked",
  "start": "2026-06-24T12:00:00+02:00",
  "end": "2026-06-24T12:30:00+02:00",
  "participant": [
    {
      "actor": {
        "reference": "Patient/e7c65c08-0131-4c12-9c3f-c30f40d39e80",
        "display": "John Doe",
        "telecom": [
          {
            "system": "phone",
            "value": "+31612345678"
          }
        ]
      },
      "status": "accepted"
    },
    {
      "actor": {
        "reference": "Practitioner/dr-alice-smith-uuid",
        "display": "Dr. Alice Smith"
      },
      "status": "accepted"
    }
  ]
}
```

### 3. Response Handling
The receiver will respond with a FHIR `OperationOutcome` payload. Ensure the sender parses this response format:
* **HTTP 200 (Success):** Response contains `"resourceType": "OperationOutcome"` with informational diagnostics acknowledging receipt.
* **HTTP 400 (Bad Request / Validation Failure):** Response contains `"resourceType": "OperationOutcome"` listing specific validation errors inside the `issue` array.
