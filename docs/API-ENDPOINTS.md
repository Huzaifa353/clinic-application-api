# Clinstra API — endpoint inventory

Generated from the running application's OpenAPI description (`/v3/api-docs`); the interactive version is Swagger UI at `/swagger-ui.html` (development only).

**118 operations** over 94 paths. All paths are under the server root; everything except `POST /api/v1/auth/login`, `GET /api/v1/events` (one-time ticket) and the docs needs `Authorization: Bearer <token>`.

Roles: the **doctor** alone changes clinical data, settings, the medicine database, clinic services and voids invoices; the **assistant** runs the front desk (registration, intake, queue editing, payments). Both can read most things. See `API-PLAN.md` for the full rules.

## Authentication

| Method | Path |
|---|---|
| POST | `/api/v1/auth/change-password` |
| POST | `/api/v1/auth/login` |
| GET | `/api/v1/auth/me` |

## Users

| Method | Path |
|---|---|
| GET | `/api/v1/users` |

## Doctor profile

| Method | Path |
|---|---|
| GET | `/api/v1/doctor-profile` |
| PUT | `/api/v1/doctor-profile` |

## Doctor status

| Method | Path |
|---|---|
| GET | `/api/v1/doctor-status` |
| PUT | `/api/v1/doctor-status` |

## Catalogs & service fees

| Method | Path |
|---|---|
| GET | `/api/v1/catalog/bootstrap` |
| POST | `/api/v1/catalog/diagnoses` |
| POST | `/api/v1/catalog/symptoms` |
| GET | `/api/v1/service-fees` |
| POST | `/api/v1/service-fees` |
| PUT | `/api/v1/service-fees/{id}` |
| DELETE | `/api/v1/service-fees/{id}` |

## Settings

| Method | Path |
|---|---|
| GET | `/api/v1/settings/integrations` |
| PUT | `/api/v1/settings/integrations` |
| GET | `/api/v1/settings/localization` |
| PUT | `/api/v1/settings/localization` |
| GET | `/api/v1/settings/print` |
| PUT | `/api/v1/settings/print` |
| POST | `/api/v1/settings/print/logo` |
| DELETE | `/api/v1/settings/print/logo` |
| POST | `/api/v1/settings/print/signature` |
| DELETE | `/api/v1/settings/print/signature` |
| GET | `/api/v1/settings/theme` |
| PUT | `/api/v1/settings/theme` |
| POST | `/api/v1/settings/theme/favicon` |
| DELETE | `/api/v1/settings/theme/favicon` |
| POST | `/api/v1/settings/theme/light-logo` |
| DELETE | `/api/v1/settings/theme/light-logo` |
| POST | `/api/v1/settings/theme/logo` |
| DELETE | `/api/v1/settings/theme/logo` |

## Urdu translations

| Method | Path |
|---|---|
| GET | `/api/v1/translations` |
| PUT | `/api/v1/translations` |
| DELETE | `/api/v1/translations` |

## Files

| Method | Path |
|---|---|
| GET | `/api/v1/files/{id}` |

## Patients

| Method | Path |
|---|---|
| GET | `/api/v1/patients` |
| POST | `/api/v1/patients` |
| GET | `/api/v1/patients/duplicates` |
| GET | `/api/v1/patients/search` |
| GET | `/api/v1/patients/stats` |
| GET | `/api/v1/patients/{id}` |
| PATCH | `/api/v1/patients/{id}` |
| PUT | `/api/v1/patients/{id}/clinical` |
| GET | `/api/v1/patients/{id}/timeline` |

## Appointments

| Method | Path |
|---|---|
| GET | `/api/v1/appointments` |
| POST | `/api/v1/appointments` |
| GET | `/api/v1/appointments/conflicts` |
| GET | `/api/v1/appointments/stats` |
| GET | `/api/v1/appointments/{id}` |
| POST | `/api/v1/appointments/{id}/arrive` |
| POST | `/api/v1/appointments/{id}/cancel` |
| POST | `/api/v1/appointments/{id}/no-show` |
| POST | `/api/v1/appointments/{id}/reschedule` |

## Queue & intake

| Method | Path |
|---|---|
| GET | `/api/v1/queue` |
| POST | `/api/v1/queue/call-next` |
| POST | `/api/v1/queue/intake` |
| PUT | `/api/v1/queue/order` |
| GET | `/api/v1/queue/stats` |
| GET | `/api/v1/queue/{id}` |
| DELETE | `/api/v1/queue/{id}` |
| PATCH | `/api/v1/queue/{id}/status` |
| PATCH | `/api/v1/queue/{id}/urgent` |
| PATCH | `/api/v1/queue/{id}/vitals` |

## Notifications

| Method | Path |
|---|---|
| GET | `/api/v1/notifications` |
| POST | `/api/v1/notifications/read-all` |
| GET | `/api/v1/notifications/unread-count` |
| POST | `/api/v1/notifications/{id}/read` |

## Live events

| Method | Path |
|---|---|
| GET | `/api/v1/events` |
| POST | `/api/v1/events/ticket` |

## Billing

| Method | Path |
|---|---|
| GET | `/api/v1/invoices` |
| POST | `/api/v1/invoices` |
| GET | `/api/v1/invoices/summary` |
| GET | `/api/v1/invoices/{id}` |
| POST | `/api/v1/invoices/{id}/payments` |
| POST | `/api/v1/invoices/{id}/void` |

## Medicine Master

| Method | Path |
|---|---|
| GET | `/api/v1/medicines` |
| POST | `/api/v1/medicines` |
| GET | `/api/v1/medicines/brands` |
| GET | `/api/v1/medicines/duplicates` |
| GET | `/api/v1/medicines/frequent` |
| GET | `/api/v1/medicines/ingredients` |
| GET | `/api/v1/medicines/manufacturers` |
| POST | `/api/v1/medicines/quick` |
| GET | `/api/v1/medicines/recent` |
| GET | `/api/v1/medicines/search` |
| GET | `/api/v1/medicines/stats` |
| GET | `/api/v1/medicines/{id}` |
| PUT | `/api/v1/medicines/{id}` |
| GET | `/api/v1/medicines/{id}/alternatives` |
| POST | `/api/v1/medicines/{id}/favorite` |
| PATCH | `/api/v1/medicines/{id}/status` |

## Consultations, prescriptions & templates

| Method | Path |
|---|---|
| GET | `/api/v1/consultations` |
| GET | `/api/v1/consultations/stats` |
| GET | `/api/v1/consultations/{id}` |
| PATCH | `/api/v1/consultations/{id}/investigations/{orderId}` |
| GET | `/api/v1/prescription-templates` |
| POST | `/api/v1/prescription-templates` |
| DELETE | `/api/v1/prescription-templates/{id}` |
| PUT | `/api/v1/visits/{visitId}/consultation` |
| GET | `/api/v1/visits/{visitId}/consultation-context` |

## Follow-ups

| Method | Path |
|---|---|
| GET | `/api/v1/follow-ups` |
| GET | `/api/v1/follow-ups/due` |
| GET | `/api/v1/follow-ups/stats` |
| GET | `/api/v1/follow-ups/{id}` |
| POST | `/api/v1/follow-ups/{id}/cancel` |
| POST | `/api/v1/follow-ups/{id}/complete` |
| PUT | `/api/v1/follow-ups/{id}/contact-status` |
| POST | `/api/v1/follow-ups/{id}/remind` |
| POST | `/api/v1/follow-ups/{id}/reschedule` |
| POST | `/api/v1/follow-ups/{id}/schedule-appointment` |

## Reports

| Method | Path |
|---|---|
| GET | `/api/v1/reports/summary` |

## Patient documents

| Method | Path |
|---|---|
| DELETE | `/api/v1/documents/{id}` |
| GET | `/api/v1/documents/{id}/file` |
| GET | `/api/v1/patients/{patientId}/documents` |
| POST | `/api/v1/patients/{patientId}/documents` |

## Audit log

| Method | Path |
|---|---|
| GET | `/api/v1/audit-log` |
