# Clinstra — Backend Context

Working context for building the Spring Boot API that replaces the Angular frontend's `localStorage` data layer.
Written from a read-through of `frontend/` (services, models, dashboards, TO_DO v1–v4, CLAUDE.md). Where something was only skimmed it is marked **(verify)**.

---

## 1. Repo layout

| Path | What |
|---|---|
| `frontend/` | Angular 19 app ("Clinstra", Angular project name `preclinic-angular`). NgModule-based, **not** standalone. Bootstrap 5 + Angular Material. |
| `backend/` | Spring Boot **4.1.1**, Java **21**, Maven wrapper. Package `com.preclinic.backend`. Deps: web (`spring-boot-starter-webmvc`), data-jpa, validation, security, actuator, devtools, lombok, **PostgreSQL + Flyway** (H2 removed). Runs on `:8080`, health at `/actuator/health`. **The REST API is complete** (118 operations, 310 tests) — see `API-PLAN.md` (plan, rules, frontend cut-over notes) and `docs/API-ENDPOINTS.md`. The Angular frontend has NOT been connected yet. |
| `frontend/TO_DO_v1..v4.md` | Original feature specs (written as prompts). v1 Patient Profile · v2 Patients/Appointments/Queue/Prescriptions/Follow-ups/Billing/Reports · v3 Medicine Master · v4 Urdu printing + print settings. |
| `frontend/CLAUDE.md` | Partly **stale** (says no backend, JSON-only data). The real data layer is localStorage-backed services under `src/app/shared/`. Still useful for conventions. |

Local toolchain: JDK 21 (Temurin) at `C:\Users\CURVE\.jdks\jdk-21.0.12.1+1`, `JAVA_HOME` set at user level. Run: `cd backend; .\mvnw.cmd spring-boot:run`. Security is JWT-based with two roles (see the status section at the end).

**Database (done)**: PostgreSQL 17.9 portable install at `C:\Users\CURVE\.pgsql` (start/stop/psql scripts in `backend/scripts/*.ps1`; db `clinstra`, user/password `clinstra`/`clinstra`, admin `postgres`/`postgres`, dev only). Schema = Flyway migrations in `backend/src/main/resources/db/migration/` V1–V17 (**the source of truth** — never edit an applied migration, add a new V18+). 17 migrations (V14 theme light logo, V15 Urdu dictionary seed, V16 catalog ordering, V17 medicine search columns), 42 tables + 3 views; V13 added `integration_settings` (WhatsApp/JazzCash/EasyPaisa; secrets stored encrypted by the app, never returned by the API), doctor print-language preference, same-patient composite FKs (visit↔appointment, consultation↔visit, follow_up↔consultation/appointment, invoice↔visit) and DB triggers for payments/invoices (no overpay, no pay on void, payments immutable, invoices voided not deleted); V12 seeds the default clinic + catalogs/service fees (no users: password hashes are produced by the app). Hibernate runs with `ddl-auto=validate`. The `BackendApplicationTests` context test now needs the local DB running.

---

## 2. Product

A clinic management system for **Pakistani GP clinics**. **Single clinic, single doctor** today (Dr. Sara Ahmed, "Clinstra Family Clinic"), but the code keeps `doctorName` as a string everywhere so multi-doctor is a possible future.

Two roles, one workflow:
- **Assistant** (receptionist/compounder): registers patients, takes vitals + fee, puts them in the queue, manages appointments, collects payments, chases follow-ups.
- **Doctor**: sees the waiting queue, starts a consultation, records clinical data + prescription, prints, moves to next patient.

**The two dashboards are the heart of the product** ("one-stop" screens). Everything else is supporting screens reached from them. Product principles (PRODUCT-ROADMAP.md): speed over completeness, doctor never does data entry that the assistant can do, mobile number is the primary patient lookup, UI stays English while *printed documents* can be English / Urdu / bilingual, PKR everywhere.

### Assistant Dashboard (`core/dashboard/assistant-dashboard`)
One screen, a context panel that switches between modes: `idle · search-results · new-patient · patient-workflow · manage-queue-entry`.
- Search (name / id / mobile / CNIC) → pick or register patient.
- **Patient workflow** = vitals (BP sys/dia, temp, pulse, weight) + service picker (fee prefilled from service catalog, editable) + discount + amount paid + payment method + urgent flag → **"Add to Queue"**.
- **Today's Queue**: drag-reorder, open an entry to edit vitals, toggle urgent, change status, remove.
- **Today's Appointments**: Check In (→ same patient workflow, linked to the appointment), No-show, Cancel.
- **Follow-ups Due**: due-today + overdue list, "mark reminded" (dummy WhatsApp), start visit.
- **Notifications**: "Dr. X started consultation for <patient> (Token #NN)" → click opens the queue entry.
- **Doctor status** (consulting / available / away), **today stats** (patients, waiting, completed, collected), fullscreen focus mode.

### Doctor Dashboard (`core/dashboard/doctor-dashboard`)
Current patient card (resume), **waiting queue** with waiting times (urgent first visually), quick patient search (read-only basics), today stats, **away toggle**. Clicking a waiting/hold entry sets it `consulting` (only if nobody is currently consulting) and opens the Consultation Room with `?token=NN`.

### Consultation Room (`core/dashboard/consultation`, doctor only)
Split screen. **Left**: patient alerts/allergies, today's vitals (abnormal BP/temp/pulse flagged), editable medical history / surgical history / current meds / allergies (these write back to the *patient*), previous visits. **Right**: symptoms + diagnoses (catalog-backed chip pickers that can add new catalog entries), clinical details (examination findings by category, investigation orders, notes), follow-up (N days), **prescription builder** (Medicine Master search, templates, frequency codes `1-0-1`/SOS/HS…, timing, duration, per-row notes), print preview in en/ur/bilingual. Actions: **Print**, **Print & Next Patient**, **Hold**. Save = upsert consultation + queue entry → `completed`. Completed entries can be reopened/edited.

---

## 3. Frontend data layer today (what the API replaces)

Every domain is a `providedIn: 'root'` service: `BehaviorSubject` + `localStorage` + a `storage` event listener for cross-tab sync (Assistant and Doctor run in two tabs). Seed data is baked into each service. **No `HttpClient` is used for domain data** (only `DataService` for leftover template JSON). Auth is fake (`AuthService`: role chosen on login, password default `123456` in localStorage).

| localStorage key | Service | Backend resource |
|---|---|---|
| `clinstra_user`, `authenticated`, `clinstra_password` | AuthService | users, auth, change-password |
| `clinstra_queue`, `_queue_token_counter`, `_queue_history` | QueueService | queue entries (+ history) |
| `clinstra_patient_directory_registered`, `_overrides` | PatientDirectoryService | patients |
| `clinstra_appointments` | AppointmentService | appointments |
| `clinstra_consultations` | ConsultationService | consultations (+ prescription, follow-up) |
| `clinstra_payments` | PaymentService | invoices + payment transactions |
| `clinstra_service_catalog` | ServiceCatalogService | service/fee list |
| `clinstra_medicine_master`, `_seed_version` | MedicineService | medicine master (7 tables) |
| `clinstra_catalog_symptoms`, `_diagnoses` | CatalogService | symptom/diagnosis catalogs |
| `clinstra_prescription_templates` | PrescriptionTemplateService | templates |
| `clinstra_patient_documents` | DocumentService | documents (metadata only today) |
| `clinstra_notifications` | NotificationService | notifications / events |
| `clinstra_doctor_away` | DoctorStatusService | doctor status |
| `clinstra_print_settings` | PrintSettingsService | clinic print settings (+ logo/signature) |
| `clinstra_doctor_profile` | DoctorProfileService | doctor profile |
| `clinstra_translations_v1` | TranslationService | Urdu translation dictionary |
| `clinstra_localization_settings`, `_theme_settings` | LocalSettingsStore | clinic settings |
| (investigation catalog, exam findings, frequency/duration/timing option lists) | CatalogService constants | seed/reference data |

Reports (`ReportsService`) are computed client-side by joining appointments + consultations + patients + payments + queue → need server-side aggregate endpoints.

---

## 4. Data model (as the frontend sees it — `shared/models/models.ts`)

**PatientRecord** — `id` (seed `S00001…`, registered `P00001…`), name, mobile, gender, age (string|number; DOB optional), address, cnic, dateOfBirth, bloodGroup, img, registrationDate, totalVisits, lastVisit (display string like `13 Aug 2026`), lastDoctor, outstandingBalance, allergies[], medicalHistory[], surgicalHistory[], currentMedications[].
- **Household mobiles**: several patients can share one mobile number (S00006–S00009 demo family). Search by mobile must return all of them. Mobile normalisation → `03XXXXXXXXX` (handles `+92`, `92`, 10-digit). Duplicate-registration warning compares the **last 10 digits** (warn, allow "register anyway").
- "Active" patient = last visit within 90 days.

**QueueEntry** — `tokenNo` (zero-padded `01`,`02`… **unique per day only**), patientId, patientName, mobile, `vitals{bpSystolic,bpDiastolic,temperature,pulse,weight,spo2?}`, `fee/discount/amountPaid` (**strings** in the UI), paymentStatus (`Paid|Partial|Unpaid`), paymentMethod (`Cash|Card|Bank Transfer|EasyPaisa|JazzCash`), status (`waiting|consulting|completed|hold|skipped|cancelled`), addedAt (ISO), source (`walk-in|appointment`), appointmentId?, urgent.
- Live queue **resets daily**; a separate never-truncated history log is kept keyed by `day#token`.
- Order in the array = display order (drag-reorder persists order), but "call next" picks oldest `waiting` by `addedAt`.

**Appointment** — id `A#####`, patientId, patientName, mobile, `date` (`yyyy-mm-dd`), `time` (string `"10:00 AM"`), doctorName, type (`Consultation|Follow-up|New Patient|Review`), status (`scheduled|arrived|checked-in|cancelled|no-show|rescheduled`), queueTokenNo?, notes, cancellationReason, rescheduledToId/FromId, reminderStatus.
- Reschedule = old row kept with status `rescheduled` + new row created and cross-linked.
- Conflict checks: same patient same day (open statuses); same doctor same date+time.

**ConsultationRecord** — id `C#####`, patientId, tokenNo, date (ISO), symptoms[], diagnoses[], notes, examinationFindings[{id,category,finding,customFinding}], investigationOrders[{investigationId,investigationName,isCustom,status Ordered|Completed,resultNote}], followUp, doctorName, prescription[PrescriptionItem], vitals.
- **PrescriptionItem**: id, medicine (name snapshot — never re-synced), frequency (code like `1-0-1`), duration (`"5 Days"`), instructions (meal timing), notes, productId? (link to Medicine Master).
- **FollowUpPlan**: enabled, days (N days after visit), reason, status (`completed|cancelled`, unset = derived), lastRemindedDate, contactStatus (`contacted|no-response|declined`), linkedAppointmentId, overrideDueDate (reschedule), cancellationReason, resolvedAt. **Due date = visit date + days unless overridden.** Upcoming / Due today / Overdue are *derived*, not stored.

**PaymentRecord (= invoice)** — id `PAY#####` (display **`INV-#####`**), patientId, tokenNo, date, consultationFee, additionalCharges, discount, total, paid, balance, paymentMethod, paymentStatus, doctorName?, description?, cancelled?/cancellationReason (void, never delete), `transactions[]`.
- **PaymentTransaction** — id **`REC######`** (receipt no.), amount, paymentMethod, reference?, paidAt, receivedBy.
- `receivePayment` caps at outstanding balance, appends a transaction, recomputes paid/balance/status; refuses on cancelled or zero-balance invoices. Transactions are immutable.
- ⚠ The **initial payment taken at intake is not a transaction** today (the patient profile has an `untrackedInitialPayment` workaround). Backend should record it as the first transaction.
- ⚠ `PatientRecord.outstandingBalance` is bumped once at intake and never decremented by `receivePayment`. Backend should **derive** outstanding from invoices.

**Medicine Master (v3)** — Ingredient, Manufacturer, Brand, MedicineComposition(+CompositionIngredient: value/unit/per-value/per-unit), MedicineProduct (brandId, compositionId, manufacturerId, productName, dosageForm, route, status, category, registrationNumber/Status, verificationStatus, aliases[], usageCount, lastUsedAt, isFavorite), MedicinePack, MedicinePriceEntry (append-only price history). Quick-add from prescription box creates an `Unverified` "Manual Entry" product with placeholder ingredient/manufacturer. Search scoring: exact product > brand > generic > alias > prefix > contains > strength digits > dosage form > manufacturer; +15 favourite, +up to 10 usage; only `Active` products. Usage/favourites are clinic-level on the product (could split to join tables later).

**Other**: ServiceFeeItem (`SVC###`, name, fee — seed: Consultation 1500, Follow-up 500, Procedure 3000, Investigation 1000, Other 0), PrescriptionTemplate (`TPL#####`, doctorName, name, medicines[]), PatientDocument (metadata only — no file bytes yet), AppNotification (`CONSULTATION_STARTED`, today-only), DoctorProfile (name, qualifications, specialization, registrationNo/PMDC, address), PrintSettings (clinic name/address/phone/whatsapp/email/website, **logo + signature as data-URLs**, showSignature, headerLayout `classic|doctor-focused|clinic-focused`, footerText, footerShowDisclaimer, defaultLanguage `en|ur|bilingual`), LocalizationSettings (timezone `(UTC+05:00) Islamabad, Karachi`, date format `DD-MM-YYYY`, 24h, currency `Rs.`), ThemeSettings.

Reference lists baked into `CatalogService`: symptoms (15), diagnoses (12), investigations (23, groups Laboratory/Imaging/Other), examination categories (9) + findings per category, frequency codes (`1-0-0 … SOS, STAT, HS, PRN`) with English meanings, durations, meal-timing options. Urdu translations live in `TranslationService` seed dictionaries per category (symptom, diagnosis, investigation, examination, frequency, duration, instruction, medicine) and are user-extendable.

---

## 5. Core workflows & rules the backend must own

1. **Add to Queue** (assistant) — one atomic action: record visit on patient (`lastVisit`, `totalVisits++`) → resolve/auto-link a same-day open appointment (`scheduled|arrived`) → create queue entry with next daily token → link appointment (`checked-in`, `queueTokenNo`) → create invoice for the visit (+ first transaction if `paid > 0`) → add outstanding if unpaid remainder. Currently split across 5 service calls in the component.
2. **Token generation** — monotonic per day per clinic, zero-padded to 2 digits, never reused after removal, resets daily (use clinic-timezone date, Asia/Karachi). Must be concurrency-safe.
3. **Start consultation** — only one `consulting` entry at a time (frontend blocks call-next/call-specific while one exists; enforce server-side). Transition to `consulting` emits a `CONSULTATION_STARTED` notification to the assistant.
4. **Queue status** — `waiting → consulting → completed`; `hold`, `skipped` (can return to `waiting`), `cancelled`. Completed can be reopened for editing.
5. **Save consultation** — upsert by (patient, visit); sets queue entry `completed`; prescription items snapshot the medicine name; medicine usage counters bump.
   ⚠ Frontend looks up the existing consultation by `(patientId, tokenNo)` but tokens repeat daily → backend must key a consultation to a **visit/queue-entry id**, not token.
6. **Patient edits from the consultation panel** (allergies/histories/medications) write to the patient record.
7. **Follow-ups** — created from consultation; derived status; assistant actions: mark reminded/contact status, schedule appointment (creates an Appointment and links it), reschedule (override due date), mark completed, cancel (reason). A follow-up *references* an appointment, it is not one.
8. **Billing** — voiding keeps the audit trail; partial/multiple payments via transactions; receipts per transaction (`REC######`), invoices `INV-#####`; payment methods incl. EasyPaisa/JazzCash; financial calculations must be server-side (spec §57); daily collection / cash reconciliation, doctor-wise and service-wise revenue.
9. **Appointments** — lifecycle above; reschedule/cancel (reason)/no-show/check-in; reminders are a future WhatsApp/SMS hook (`reminderStatus` field only).
10. **Printing** — documents are generated **client-side** (HTML → browser print / jsPDF-html2canvas, Noto Nastaliq Urdu font in `assets/fonts`). Backend's job is to *store* print/clinic/doctor settings, logo & signature, translation dictionary — not to render PDFs (unless WhatsApp delivery later). Language priority: per-document > doctor/user pref > clinic default > `en`. Never auto-translate medicine names, strengths, IDs, phone numbers.

### Roles / permissions (existing model: `UserRole = 'doctor' | 'assistant'`, `RoleGuard` with `data.roles`)
- Assistant-only routes: assistant dashboard. Doctor-only: doctor dashboard, consultation room.
- Shared screens (queue, patients, appointments, prescriptions, follow-ups, billing, analytics, settings, medicine library) branch on `isDoctor`/`isAssistant` inside the component. Specs (v1 §17, v2 §16/31, queue §31) say: assistant = registration, demographics, appointments, queue operations, payments, follow-up contact; doctor = clinical data, prescriptions, start/complete visit; assistant must not freely edit clinical data; doctor not necessarily admin of queue/appointments. Billing "sensitive actions" (void/refund) rules in TO_DO_v2 §54–55 **(verify)**.
- Spec rule repeated everywhere: *"use the existing permission system, do not invent a new framework."*

---

## 6. Conventions, formats & gotchas

- **Dates**: mix of ISO strings, `yyyy-mm-dd`, and display strings (`13 Aug 2026`, `lastVisit`) — normalise at the API boundary; store real dates/timestamps. Appointment `time` is `"hh:mm AM/PM"` text. Timezone `Asia/Karachi`.
- **IDs**: prefixed counters (`P/S#####`, `A#####`, `C#####`, `PAY#####`, `REC######`, `TPL#####`, `DOC#####`, `SVC###`, tokens `NN`). Frontend derives them from `array.length + 1` (collision-prone). Backend: DB surrogate keys + a human-readable display number; keep the prefixed format in API responses so the UI still looks the same.
- **Money**: PKR, integers in practice (no paisa); `QueueEntry` money fields are strings in the UI — API should use numbers and the UI service adapts.
- **Seed data**: the demo patients (`S00001–S00009`), appointments, consultations, payments and medicine master must be reproducible as dev seed data (relative dates for "today" items).
- **Medicine names** are snapshotted into prescriptions; history must not change if the master is edited/deactivated.
- **Search** must be fast on name, id, mobile (normalised), CNIC (dashes stripped).
- **Real-time**: Assistant ↔ Doctor currently sync via `storage` events. Needs polling or SSE/WebSocket for queue, notifications, doctor status, appointments.
- **Frontend conventions** worth mirroring in API shape: list screens use `{data, totalData}` (`apiResultFormat`) with skip/limit paging, sort, filters; `routes.ts` is the single source of route strings; module triad per feature.
- **Leftover template code** (accounts, payroll, blogs, chat, calls, email, staff, invoices, assets, `assets/json/*`, admin & patient dashboards) is stock "Preclinic" template — **not part of the product**; ignore for the backend.
- CORS origin for dev: `http://localhost:4200`. No `environment.ts` exists on the frontend yet — an API base URL config will need to be introduced when wiring up.
- Frontend `README`/`DEMO.md` credentials: `admin@clinstra.com` / `123456`; assistant demo user Sana Tariq `sana.tariq@clinstra.com`.

---

## 7. Proposed backend shape (not built — for discussion)

Domain packages: `auth`, `patient`, `appointment`, `queue`, `consultation` (incl. prescription, follow-up), `billing` (invoice, transaction, service catalog), `medicine`, `catalog` (symptoms/diagnoses/investigations/exam), `template`, `document`, `notification`, `settings` (clinic/print/doctor/localization/translation), `report`.

Endpoint groups (REST, JSON, `/api/v1`):
- `POST /auth/login`, `GET /auth/me`, `POST /auth/change-password`
- `GET/POST /patients`, `GET /patients/search?q=`, `GET/PATCH /patients/{id}`, `GET /patients/{id}/{visits|billing|documents|timeline}`
- `GET/POST /appointments`, `PATCH …/{id}/{check-in|cancel|no-show|reschedule}`
- `GET /queue` (today), `GET /queue/history?date=`, `POST /queue` (**the atomic "add to queue" intake**), `PATCH /queue/{id}/{status|vitals|urgent|order}`, `DELETE /queue/{id}`
- `GET/PUT /consultations`, `GET /consultations/by-visit/{queueEntryId}`, follow-up actions under `/follow-ups`
- `GET /invoices`, `POST /invoices/{id}/payments`, `POST /invoices/{id}/void`
- `/medicines` (search, CRUD, usage, favourite), `/catalog/*`, `/templates`, `/service-fees`
- `/settings/{print|localization|theme|doctor-profile|translations}`, logo/signature upload
- `/notifications` (+ SSE stream), `/doctor-status`
- `/reports/summary?from&to&doctor` (aggregate for the Reports screen)

Stack decisions still open (see §8).

---

## 8. Open decisions to settle before coding

1. ~~Database~~ — **decided: PostgreSQL + Flyway** (schema implemented in V1–V12).
2. **Auth** — JWT (stateless, simplest with Angular) vs session cookie. Real users table with `doctor`/`assistant` roles; password hashing; multi-user from day one?
3. **Multi-clinic / multi-doctor** — keep single-clinic now but model `clinic_id` + `doctor_id` entities, or stay strictly single?
4. **Queue sync** — polling vs SSE vs WebSocket for Assistant↔Doctor live updates.
5. **File storage** — logo/signature/documents: DB (bytea/base64) vs filesystem vs object storage.
6. **ID strategy** — surrogate PKs + display numbers, and the exact format of invoice/receipt/token numbering.
7. **Frontend cut-over** — replace each localStorage service with an HTTP-backed implementation behind the same public methods (components stay unchanged) — incremental, one service at a time? Starting point suggestion: auth → patients → queue/intake → appointments → consultations → billing → medicines → settings → reports.
8. **Seed data** — port the demo seed (patients, appointments, medicines, translations) into backend seed scripts.

---

## 9. Not read in detail (so treat as unverified)

TO_DO_v2 bodies beyond the section headings and role sections (Patients/Appointments/Queue/Prescriptions/Follow-ups/Billing/Reports are ~11k lines); TO_DO_v3 beyond headings; the bodies of `patient-profile`, `billing`, `follow-ups`, `analytics`, `medicine-master` components; the `print-document.service` HTML templates; settings pages other than their service wiring. Re-read the relevant TO_DO section and component before implementing each backend module.


---

## Backend status (as of 2026-10-07)

- **Done:** Spring Boot 4.1.1 / Java 21 API under `/api/v1` — JWT auth, patients, appointments, queue + atomic intake, billing, consultations/prescriptions, medicine master, follow-ups, reports, documents/timeline, settings, translations, audit log, live events (SSE). 310 tests (MockMvc + real PostgreSQL `clinstra_test`, plus real-commit concurrency tests).
- **Run:** `backend\scripts\pg-start.ps1`, then `backend\mvnw.cmd spring-boot:run`; Swagger UI at http://localhost:8080/swagger-ui.html; dev logins `admin@clinstra.com` (doctor) / `sana.tariq@clinstra.com` (assistant), password `123456`.
- **Next:** re-point the Angular services at the API one slice at a time (`API-PLAN.md` section 9), starting with Auth and an `environment.ts` with the API base URL + an HTTP interceptor for the bearer token.
- **Conventions worth knowing:** data layer after identity is SQL-first via `JdbcClient` (not JPA entities); every query is scoped by the clinic id from the JWT; money/number-sequence/uniqueness rules live in the database too (triggers, partial unique indexes); errors are `ProblemDetail` with a stable `code`; Jackson 3 rejects a missing primitive `boolean` in request records (use `Boolean`); to write SQL `escape '\'` inside a normal Java string, type `escape '\\'` (two backslashes; four is a bug, one breaks the string).
