# Clinstra Backend — API Plan

## Progress

**The backend API is complete (all 11 phases): 118 operations over 94 paths, 310 automated tests passing, schema = Flyway V1–V17.** Full list: `docs/API-ENDPOINTS.md` (generated); interactive docs: Swagger UI at `/swagger-ui.html` (dev only). Next step is **cutting the Angular frontend over** to this API (section 9).

| Phase | Status |
|---|---|
| 0 Foundation | Done — error format (`ProblemDetail` + `code`), clinic clock, atomic number sequences, JWT security + CORS, audit log, secret encryption, files, SSE bus |
| 1 Auth & identity | Done — login / me / change-password, users, doctor profile, doctor status |
| 2 Settings & catalogs | Done — catalog bootstrap, service fees, print/localization/theme/integration settings, logo/signature uploads, Urdu dictionary (113 seeded entries, V15) |
| 3 Patients | Done — search (any mobile format, households), duplicates, register, edit, doctor-only clinical lists, stats |
| 4 Appointments | Done — book, conflicts, arrive / cancel / no-show / reschedule, day stats |
| 5 Billing | Done — invoices, partial payments, receipts, void (doctor only), summary; DB triggers back every money rule |
| 6 Queue & intake | Done — atomic intake, status moves, call-next, reorder, vitals, urgent, notifications, SSE live events |
| 7 Medicine Master | Done — ranked search, library CRUD with price history, duplicates, quick-add, favourites/usage per clinic, alternatives |
| 8 Consultation | Done — context in one call, atomic save (draft or complete), prescription + usage counting, follow-up plan, templates, history |
| 9 Follow-ups | Done — tabs, stats, remind, contact outcome, reschedule, complete, cancel, schedule a real appointment |
| 10 Reports | Done — one summary endpoint with trends, doctor rows, services, outstanding list |
| 11 Documents & hardening | Done — patient documents, timeline, audit viewer, deactivated-user tokens rejected immediately, login lockout, password policy, prod profile + startup safety check |

Decisions taken: JWT access token only (12h), SSE with a one-time ticket, numeric id + display number on the wire, separate `clinstra_test` database, void = doctor-only. SQL-first data layer (`JdbcClient`) for everything after the identity tables.

Run: `.\scripts\pg-start.ps1` then `.\mvnw.cmd spring-boot:run` (dev seed logins `admin@clinstra.com` doctor / `sana.tariq@clinstra.com` assistant, password `123456`; 16 demo medicines are loaded when the medicine table is empty). Tests: `.\mvnw.cmd test` (needs PostgreSQL running; uses the `clinstra_test` database).
Production: `SPRING_PROFILES_ACTIVE=prod` plus the environment variables in `application-prod.properties` (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET` ≥48 chars, `SECRETS_KEY` ≥32 chars, `CORS_ORIGINS`); the app refuses to start with development defaults.

## 1. Principles

1. **Vertical slices.** Each phase delivers one working end-to-end capability (API + tests + frontend cut-over), not "all controllers, then all services".
2. **The two dashboards drive the order.** The Assistant flow (search → register → intake → queue) and the Doctor flow (call → consult → print → next) must work on the real API as early as possible.
3. **The database already enforces the hard rules** (one consulting patient per doctor, unique tokens, no double-booking, immutable payments, no overpayment, same-patient FKs). The API's job is to run business operations *atomically* and translate DB violations into clean `409/422` errors — not to re-implement the rules.
4. **Server owns the calculations**: totals, balance, payment status, outstanding, follow-up due state, token numbers, report aggregates. The frontend only displays.
5. **API shapes stay close to the frontend models** (`shared/models/models.ts`) so each Angular service can be re-pointed at HTTP with its public method signatures mostly intact.
6. **Roles reuse the existing model**: `doctor | assistant`. No new permission framework.

## 2. Architecture

```
com.preclinic.backend
 ├─ common/        ApiError (RFC 7807 ProblemDetail), exception handler, ClinicContext, ClinicClock,
 │                 NumberSequenceService, paging types, base entity bits, SSE event bus
 ├─ security/      JWT filter, SecurityConfig (CORS for :4200), password encoder, @CurrentUser
 ├─ auth/ user/ settings/ catalog/ patient/ appointment/ queue/ consultation/
 │  medicine/ billing/ followup/ report/ document/ notification/
 │    each:  *Controller → *Service (@Transactional) → *Repository (Spring Data JPA) + entity + DTO records
```

- **Layers**: controller (HTTP + validation only) → service (transactions, rules, authorization of business actions) → repository. Entities never leave the service layer; controllers speak **DTO records**.
- **Mapping**: JPA entities map the existing tables (`ddl-auto=validate` will catch drift). Hand-written mappers (no MapStruct) to keep it explicit.
- **Queries**: Spring Data derived queries for simple cases; JPQL / native SQL for search, reports and the number-sequence upsert. Reports may read the SQL views directly.
- **Clinic scoping**: `clinic_id` comes from the JWT, never from the request. Every repository method takes `clinicId`. (One clinic today, but nothing leaks if that changes.)
- **Time**: one `ClinicClock` bean (zone from `clinic.timezone`, default `Asia/Karachi`). "Today" for queue/tokens/follow-ups always means clinic-local date, never server-local.
- **Numbers**: `NumberSequenceService.next(kind, scope)` = the atomic `INSERT … ON CONFLICT DO UPDATE … RETURNING` from V2, always called inside the caller's transaction.
- **Errors**: `400` malformed, `401/403` auth, `404`, `409` conflict (unique/exclusion violations, state conflicts), `422` business-rule failure. Body = ProblemDetail with a stable `code` (e.g. `QUEUE_DOCTOR_BUSY`, `PAYMENT_EXCEEDS_BALANCE`) the frontend can switch on.
- **IDs on the wire**: every resource returns the numeric `id` **and** its display number (`patientNo`/`displayId` such as `P00001`, `tokenNo` `"01"`, `invoiceNo` `INV-00001`, `receiptNo` `REC000001`). URLs use numeric ids. The frontend adapters map to its existing string ids.
- **Lists**: server-side filter/sort/paging for large sets (patients, invoices, visit history, consultations) returning `{ data, totalData }` (same envelope as the frontend's `apiResultFormat`), params `skip`/`limit`/`sort`/filters. Small bounded sets (today's queue, catalogs, templates) return plain arrays.
- **Money**: JSON numbers (PKR, 2dp, `BigDecimal` server-side). Dates `yyyy-MM-dd`, instants ISO-8601 UTC, appointment time `HH:mm` (frontend formats "10:00 AM").
- **Docs/tests**: springdoc OpenAPI for the contract; JUnit 5 + MockMvc/`@SpringBootTest` against a dedicated `clinstra_test` PostgreSQL database (Docker isn't installed, so no Testcontainers for now; Flyway builds the schema on first run).

## 3. Cross-cutting design decisions (defaults — shout if you disagree)

| Topic | Default | Why |
|---|---|---|
| Auth | **JWT** (Bearer header), BCrypt passwords, ~12h access token (a clinic day); refresh tokens deferred | Simplest with Angular; no session store |
| Roles | `@PreAuthorize` on service methods using `hasRole('DOCTOR')`/`'ASSISTANT'` | Reuses the 2-role model |
| Real-time | **SSE** channel `GET /api/v1/events` (typed events: `queue`, `appointments`, `notification`, `doctor-status`, `invoice`); the frontend refetches the affected resource. Start with 5s polling in the frontend, switch to SSE in the queue slice | Replaces the `storage`-event cross-tab sync; SSE is one-way, simple, proxy-friendly |
| Files | logo/signature/documents in `file_asset.data` (bytea), served by `GET /files/{id}`; size/type validated (images ≤2 MB, docs ≤10 MB) | Matches V1; swap storage later |
| Secrets | WhatsApp/JazzCash/EasyPaisa secrets encrypted (AES-GCM, key from env var) before insert; API returns only `…Set: true/false` | V13 `*_enc` columns |
| Audit | `audit_log` rows for: invoice void, payment received, visit status change, patient edit, settings change | Financial/clinical traceability |
| Seed | Dev profile bootstraps one doctor + one assistant (`admin@clinstra.com` / `sana.tariq@clinstra.com`, password from env/`123456` in dev) and optional demo data | V12 deliberately creates no users |

## 4. Build phases

Each phase ends with: endpoints done → integration tests green → frontend service(s) re-pointed → manual run-through.

### Phase 0 — Foundation
Common error handling, `ClinicContext`/`ClinicClock`, `NumberSequenceService`, security skeleton + CORS, SSE bus, OpenAPI, test setup (`clinstra_test`), JPA base conventions. **Frontend**: add `environment.ts` (`apiUrl`), an `HttpClient` interceptor that adds the JWT and handles 401.

### Phase 1 — Auth & identity
| Endpoint | Notes |
|---|---|
| `POST /auth/login` | `{email,password}` → `{token,user{id,name,role,clinic,email}}` |
| `GET /auth/me` · `POST /auth/change-password` | replaces `AuthService` localStorage logic |
| `GET /users?role=doctor` | doctor picker for appointments |
| `GET/PUT /doctor-profile` (me) | qualifications, PMDC no., address, preferred print language |
| `GET/PUT /doctor-status` | away flag; "consulting" derived from queue |
**Frontend**: `AuthService`, `RoleGuard` (reads role from token), `DoctorProfileService`, `DoctorStatusService`.

### Phase 2 — Settings & reference data
| Endpoint | Notes |
|---|---|
| `GET /catalog/bootstrap` | **one call** returning symptoms, diagnoses, investigations, examination findings by category, frequency codes (+meaning), duration/timing options — the frontend reads these synchronously |
| `POST /catalog/symptoms` · `POST /catalog/diagnoses` | add-on-the-fly from the consultation chip pickers; case-insensitive de-dupe returns the existing canonical row |
| `GET/POST/PUT/DELETE /service-fees` | Settings → Services & Fees; assistant dashboard fee picker |
| `GET/PUT /settings/print` · `POST/DELETE /settings/print/logo` · `/signature` | multipart upload, returns file id |
| `GET /files/{id}` | binary with correct content type, cacheable |
| `GET/PUT /settings/localization` · `/settings/theme` | |
| `GET/PUT /settings/integrations` | write-only secrets; `GET` returns booleans |
| `GET/PUT/DELETE /translations?category=` | Urdu dictionary used by printing |
**Frontend**: `CatalogService`, `ServiceCatalogService`, `PrintSettingsService`, `LocalSettingsStore` users, `TranslationService`.

### Phase 3 — Patients
| Endpoint | Notes |
|---|---|
| `GET /patients?q&status&followUp&sort&skip&limit` | Patients screen; `status` = active (visit ≤90 days) / inactive |
| `GET /patients/search?q=` | assistant & doctor quick search: name, patientNo, **normalised mobile** (returns all household members), CNIC; top ~20 |
| `GET /patients/duplicates?mobile=` | last-10-digit match → used for the "register anyway" warning |
| `POST /patients` | server assigns `patient_no`, normalises mobile; **does not** block duplicates (warning is a separate call) |
| `GET /patients/{id}` · `PATCH /patients/{id}` | demographics (both roles) |
| `PUT /patients/{id}/clinical` | allergies/histories/medications — **doctor only** |
| `GET /patients/{id}/summary` | totalVisits, lastVisit, lastDoctor, outstanding, next follow-up, alerts (from the V5/V10 views) |
**Frontend**: `PatientDirectoryService` (keep `search/getById/register/updatePatient`; `getById` becomes a cached async fetch).

### Phase 4 — Appointments
| Endpoint | Notes |
|---|---|
| `GET /appointments?date&from&to&status&doctorId&patientId&q` | day list, calendar range, patient history |
| `POST /appointments` | assigns `appointment_no`; `409 APPOINTMENT_SLOT_TAKEN` from the partial unique index |
| `GET /appointments/conflicts?patientId&date&doctorId&time` | pre-checks for the two soft warnings (same patient/day, doctor slot) |
| `POST /appointments/{id}/arrive` · `/cancel {reason}` · `/no-show` · `/reschedule {date,time,notes}` | reschedule = mark old `rescheduled`, create new with `rescheduled_from_id`, one transaction |
(Check-in-to-queue happens in the intake call, Phase 6.)
**Frontend**: `AppointmentService`.

### Phase 5 — Billing core
| Endpoint | Notes |
|---|---|
| `GET /invoices?range&status&method&doctorId&q&skip&limit` | Billing screen |
| `GET /invoices/summary?from&to` | collected / billed / outstanding / by-method cards, daily collection |
| `GET /invoices/{id}` | invoice + payment transactions + patient + doctor (print data) |
| `POST /invoices` | manual/walk-in charge (intake creates its own, Phase 6) |
| `POST /invoices/{id}/payments` | `{amount,method,reference}`; locks invoice; DB trigger is the backstop → `422 PAYMENT_EXCEEDS_BALANCE`/`INVOICE_VOID`; returns the receipt (`REC######`) |
| `POST /invoices/{id}/void {reason}` | audit-logged; never deletes |
| `GET /patients/{id}/invoices` | patient profile Billing tab |
Roles: assistant records payments and prints receipts; **void is doctor-only** (TO_DO_v2 §54–55 — not every assistant may alter financial history).
**Frontend**: `PaymentService`.

### Phase 6 — Queue & intake  ⟵ **Assistant dashboard goes live**
| Endpoint | Notes |
|---|---|
| `GET /queue/today` | ordered by `sort_order`; each item includes patient basics, vitals, invoice summary (fee, discount, paid, status, method) |
| `POST /queue/intake` | **the atomic "Add to Queue"** — one transaction: resolve/auto-link same-day open appointment → next token (`number_sequence`, scope = clinic-local date) → insert `visit` (vitals, urgent, `sort_order` = max+1) → set appointment `checked-in` → create `invoice` (+ first `payment` if paid > 0) → audit. Returns the new queue item |
| `PATCH /queue/{id}/status` | `waiting→consulting→completed`, `hold`, `skipped→waiting`, `cancelled`; on → `consulting`: set `doctor_id`, `consult_started_at`, create `CONSULTATION_STARTED` notification; `409 QUEUE_DOCTOR_BUSY` from the unique index |
| `POST /queue/call-next` | oldest `waiting` by `checked_in_at`, `FOR UPDATE SKIP LOCKED` |
| `PATCH /queue/{id}/vitals` · `/urgent` | assistant edits |
| `PUT /queue/order {orderedIds[]}` | drag-reorder, rewrites `sort_order` |
| `DELETE /queue/{id}` | allowed only if no consultation exists; otherwise use `cancelled` |
| `GET /queue/history?date&status&q&doctorId&skip&limit` | Queue screen history |
| `GET /queue/stats` | today: patients / waiting / completed / collected |
| `GET /notifications` · `POST /notifications/{id}/read` · `/read-all` | unread for the caller's role |
| `GET /events` (SSE) | `queue`, `appointments`, `notification`, `doctor-status` |
**Frontend**: `QueueService`, `NotificationService`, `DoctorStatusService`, the Assistant and Doctor dashboards' subscriptions. This is the riskiest cut-over (see §6).

### Phase 7 — Medicine master
| Endpoint | Notes |
|---|---|
| `GET /medicines/search?q&limit` | the prescription box; ranking from the frontend (`exact > brand > generic > alias > prefix > contains > strength > form > manufacturer`, +15 favourite, +≤10 usage), `Active` only; implemented with pg_trgm + score expression |
| `GET /medicines/frequent` · `/recent` | per-clinic `clinic_medicine` |
| `GET /medicines?q&status&form&manufacturer&skip&limit` · `GET /medicines/{id}` (detail with composition, packs, latest prices) | Medicine Library screen |
| `POST /medicines` · `PUT /medicines/{id}` · `PATCH /medicines/{id}/status` | full master CRUD with ingredients + packs + price entry (append-only) |
| `GET /medicines/duplicates?brand&form&ingredients` | pre-save duplicate detection |
| `POST /medicines/quick {name}` | "type it and go": `Unverified`, `Manual Entry`, placeholder ingredient/manufacturer, clinic-owned |
| `POST /medicines/{id}/favorite` | toggle |
| `GET /medicines/{id}/alternatives` | same composition, other brands |
| `GET /ingredients` · `/manufacturers` · `/brands` | autocomplete for the add/edit form |
**Dev seed**: port the Pakistan demo medicine dataset (`MedicineService.seedStore()`) into a dev-only seed.
**Frontend**: `MedicineService`, `CatalogService.medicines`.

### Phase 8 — Consultation & prescriptions  ⟵ **Doctor dashboard goes live**
| Endpoint | Notes |
|---|---|
| `GET /queue/{visitId}/consultation-context` | everything the consult screen needs in one call: patient (alerts, lists), today's vitals, previous visits, existing consultation if any |
| `PUT /visits/{visitId}/consultation` | **upsert the whole consultation document** (symptoms, diagnoses, notes, examination, investigations, prescription items, follow-up plan) in one transaction: replace child rows, snapshot names, compute follow-up `due_date`, bump `clinic_medicine.usage_count` for newly added products, set visit `completed` (+`completed_at`). Idempotent; used by Print / Print & Next / reopen-edit |
| `POST /queue/{visitId}/hold` | convenience → status `hold` |
| `GET /patients/{id}/consultations` | history (visits tab, left panel) |
| `GET /consultations?q&doctorId&from&to&skip&limit` · `GET /consultations/{id}` | Prescriptions screen |
| `PATCH /consultations/{id}/investigations/{invId}` | mark `Completed` + result note |
| `GET/POST/DELETE /prescription-templates` | per doctor |
Doctor-only. Print/PDF stays client-side; the API supplies print settings, doctor profile, translations (Phase 2) and the consultation/invoice documents.
**Frontend**: `ConsultationService`, `PrescriptionTemplateService`, consultation component's save path.

### Phase 9 — Follow-ups
| Endpoint | Notes |
|---|---|
| `GET /follow-ups?tab=due\|overdue\|upcoming\|completed&q&contact&skip&limit` | state derived in SQL from `coalesce(override_due_date,due_date)` vs clinic-local today |
| `GET /follow-ups/due` | assistant dashboard "Follow-ups Due" (due today + overdue, open only) |
| `POST /follow-ups/{id}/remind` · `PATCH …/contact-status` | `last_reminded_on`, `contact_status` |
| `POST /follow-ups/{id}/schedule-appointment` | creates an appointment and links it (`linked_appointment_id`) in one transaction |
| `POST /follow-ups/{id}/reschedule {date}` · `/complete` · `/cancel {reason}` | |
**Frontend**: the follow-up half of `ConsultationService`, Follow-ups screen, dashboard widget.

### Phase 10 — Reports
`GET /reports/summary?from&to&doctorId` returns the exact `ReportsData` shape the screen uses (patients, appointments, queue, doctors, financial + by-method + trend, follow-ups + overdue list, services, outstanding invoices). SQL aggregation with `generate_series` buckets (day/week/month by range). Frontend `ReportsService` becomes a thin HTTP call. Doctors see their own; assistants per existing screen rules.

### Phase 11 — Documents, patient profile, hardening
- `GET/POST /patients/{id}/documents` (multipart) · `GET /documents/{id}/file`.
- `GET /patients/{id}/timeline` (visits, prescriptions, follow-ups, payments merged, with filter) to replace the profile's client-side join.
- Audit-log viewer endpoint (admin-ish), rate limiting on login, password policy, secrets via env, production profile + CORS origin config, Flyway baseline checks, backup notes.

## 5. Business rules map (where each lives)

| Rule | Enforced by |
|---|---|
| One consulting patient per doctor | DB partial unique index → API maps to `409 QUEUE_DOCTOR_BUSY` |
| Token unique per clinic per day, never reused | `number_sequence` upsert + DB unique |
| Intake is all-or-nothing | single `@Transactional` service method |
| No overpayment / no payment on void / payments immutable / invoices never deleted | DB triggers + service pre-checks for friendly errors |
| Same patient across appointment/visit/consultation/follow-up/invoice | composite FKs (V13) |
| Doctor slot not double-booked | partial unique index → `409` |
| Outstanding / paid / status derived | views `invoice_summary`, `patient_outstanding` |
| Follow-up Upcoming/Due/Overdue derived | SQL on effective due date, clinic-local today |
| Assistant can't edit clinical data | `@PreAuthorize` doctor-only on `PUT /patients/{id}/clinical` and consultation endpoints |
| Medicine name snapshotted in prescriptions | copied at save; `product_id` is traceability only |
| Secrets never returned | DTOs expose only "is set" flags |

## 6. Frontend cut-over strategy (and its main risk)

Every domain service is currently **synchronous in-memory** (`getById`, `getEntry`, `getHistory` return values immediately; components rely on that). HTTP is async, so each service becomes:

1. **Cache + stream**: keep the `BehaviorSubject`; populate it from the API on load/refresh; mutations call the API, then update the cache from the response.
2. **Keep public method names**; synchronous getters read the cache. Where data is too big to cache (patients, medicines, invoices, history) the method becomes `Observable`-returning and the few callers are updated.
3. **Real-time**: replace the `window 'storage'` listeners with the SSE stream (or polling at first).
4. **Order**: Auth → Settings/Catalog → Patients → Appointments → Billing → Queue/Intake → Medicines → Consultation → Follow-ups → Reports. After Phase 6 the Assistant dashboard runs fully on the API; after Phase 8 the Doctor flow does too.
5. Keep localStorage fallbacks **off** once a slice is cut over (single source of truth); keep seed data only in the backend dev seed.

Biggest risks: (a) sync→async changes inside the two dashboard components; (b) the intake and consultation save flows (multi-table, must be atomic and idempotent); (c) real-time queue consistency between two browsers.

## 7. Test plan per slice
- **Service/integration tests** (MockMvc + real PostgreSQL): happy path, each business rule above, role denial (403), clinic scoping, validation errors.
- **Concurrency tests** for the high-risk rules: two parallel intakes get distinct tokens; two parallel "consulting" updates → one `409`; two parallel payments can't overpay.
- **Contract**: OpenAPI generated and reviewed against the frontend models before re-pointing each service.
- **Manual**: run both dashboards in two browser windows after Phases 6 and 8.

## 8. Decisions needed before Phase 0
1. JWT access-token only (recommended) vs also refresh tokens now.
2. SSE for real-time (recommended) vs polling-only.
3. Expose numeric ids + display numbers (recommended) vs make display ids the primary key on the wire.
4. Test strategy: separate local `clinstra_test` DB (recommended; no Docker) vs install Docker for Testcontainers.
5. Who may **void** an invoice / take refunds. Spec (TO_DO_v2 §54–55): assistants record payments and print receipts; void/refund/changing financial history are for a clinic admin and must **not** be open to every assistant. There is no admin role today (only `doctor`/`assistant`), so recommendation: **void = doctor only**; refunds are not in the frontend, so deferred. Confirm what the billing screen currently allows before Phase 5.

---

## 9. Frontend cut-over notes (what the new API changes for the Angular app)

Behaviour differences the frontend must adapt to when each `localStorage` service is replaced:

- **Auth**: `POST /auth/login` returns `{token, expiresAt, user}`; send `Authorization: Bearer <token>` on every call; on `401 UNAUTHENTICATED` return to login. Roles stay `doctor | assistant`. New passwords need 8+ characters with a letter and a number; 5 wrong passwords lock that account+address out for 15 minutes (`429 TOO_MANY_ATTEMPTS`).
- **IDs**: every resource has a numeric `id` plus a display number (`displayId` / `tokenNo`: `P00001`, `A00001`, `C00001`, `INV-00001`, `REC000001`, token `"01"`). Use `id` in URLs and calls; show the display number.
- **Money / vitals are numbers**, not strings (the old queue entry held strings). Dates are `yyyy-MM-dd`, times `HH:mm` (24h — format to 12h in the UI), instants ISO-8601.
- **Queue entry = visit.** `QueueItemDto.id` is what every queue action uses; `billing` replaces `fee / discount / amountPaid / paymentStatus / paymentMethod`. The daily reset is gone: today = `GET /queue`, history = `GET /queue?date=`.
- **Intake** is one call (`POST /queue/intake`) that also creates the invoice, first payment, token and appointment check-in — replace the five separate service calls in the Assistant dashboard. It answers `409 PATIENT_ALREADY_IN_QUEUE` for a patient already waiting (offer "add anyway" → `?force=true`).
- **Void invoice is doctor-only** (spec: not every assistant may alter financial history). The Billing screen currently shows "Void Invoice" to the assistant; move that action to the doctor view. Both roles may receive payments.
- **Consultation save** is one call (`PUT /visits/{id}/consultation`, `complete=true|false`); the prescription field the frontend calls `instructions` (meal timing) keeps that name. `GET /visits/{id}/consultation-context` loads patient, vitals, history, any draft and the next waiting patient together.
- **Derived values are server-side**: invoice `paid/balance/paymentStatus`, patient `totalVisits/lastVisit/outstandingBalance/followUp`, follow-up `state/daysOverdue`, all report figures. Do not recompute them in the browser.
- **Live updates**: `POST /events/ticket` → `new EventSource('/api/v1/events?ticket=…')`; event names `queue`, `appointments`, `invoice`, `notification`, `doctor-status`, `consultation`, `follow-up` mean "refetch that resource". Replaces the `storage`-event cross-tab sync (polling every 5 s is a safe fallback).
- **Files** (logo, signature, documents) are fetched with the bearer token (`GET /files/{id}`, `GET /documents/{id}/file`) — an `<img src>` cannot send the header, so fetch as a blob and convert, or request through the HttpClient.
- **Settings pages**: integrations never return secrets (only `…Set` flags); send a secret only to change it (`""` clears).
- Error bodies are `{status, detail, code, errors?}`; branch on `code`.

Suggested order, each service keeping its public method names where possible: Auth → Settings/Catalog → Patients → Appointments → Billing → Queue/Intake (+ events) → Medicines → Consultation → Follow-ups → Reports → Documents/Timeline.

### Frontend cut-over progress

| Slice | State |
|-------|-------|
| Auth (login, change password, token + 401 handling) | Done — verified in Chrome (`01-auth`) |
| Settings & catalogs (print, profile, localization, theme, service fees, suggestion lists, Urdu dictionary) | Done — verified in Chrome (`02-settings`). These are loaded once after login by `CoreDataService` (called from `AuthGuard`) so screens and the print engine keep reading them synchronously. The time zone is read-only in the UI. |
| Patients (list with server filters/sort/paging, register + duplicate warning, edit, profile header + clinical lists, dashboard searches) | Done — verified in Chrome (`03-patients`). `PatientDirectoryService` keeps a cache of loaded patients so screens that still read synchronously by id work for patients already seen; the profile's visit/billing/document tabs still read browser data until their slices. |
| Appointments (day/list/search, stats, check-in, reschedule, cancel, no-show, booking with conflict warnings, dashboard list) | Done — verified in Chrome (`04-appointments`). Times are shown in 12-hour form and sent as 24-hour; a taken doctor slot can never be overridden, "Book Anyway" only covers the same patient twice in a day. Checking an appointment in to the queue is part of the Queue/Intake slice. |
| Billing (invoices with server filters/sort/paging, summary cards, receive payment + receipt, void, profile billing tab) | Done — verified in Chrome (`05-billing`). Void is now doctor-only; both roles receive payments. Until the Queue/Intake slice, the Assistant dashboard's "Add to Queue" still adds to the browser queue but saves its invoice through `POST /invoices`. |
| Queue/Intake, live events, notifications, doctor status | Done — verified in Chrome with an assistant and a doctor browser side by side (`06-queue`): one-call intake (token + visit + invoice + payment + appointment check-in), "already in queue" warning, drag order saved, remove = cancelled (kept in history), doctor-busy refusal, "Patient Called" banner and doctor Away status arriving live. Screens identify a visit by its id (`?visit=`), not the token. **Live stream caveat:** each open tab holds one server connection; browsers allow 6 per server over HTTP/1.1, so serve the app over HTTP/2 in production (the page also closes its stream when it is hidden/unloaded). The consultation screen still saves consultations in the browser until its slice. |
| Medicines (Medicine Library with server search/filter/sort/paging, add with duplicate warning, edit, deactivate; ranked prescription search and one-step "add unknown medicine") | Done — verified in Chrome (`07-medicines`). The Library's Add/Edit/Deactivate are doctor-only. Usage counts are updated by the server when a consultation is saved, so the browser no longer records usage. |
| Consultation (+ templates, Prescriptions screen), Follow-ups, Reports | Code complete and compiling; consultation/follow-up flow passed its browser test once (`08-consultation.js`), needs a clean re-confirmation; Reports has no dedicated test yet. See `../FRONTEND-CONNECTION-STATUS.md`. |
| Documents & patient timeline, Payment/WhatsApp settings | Not started — see `../FRONTEND-CONNECTION-STATUS.md` §4 |

While slices are in progress the app is deliberately in a mixed state: patients/settings/auth come from the server, the rest still from the browser's storage, so the queue, appointments, billing, history and reports of the old demo data do not line up with server patients until their slices land.

## 10. Not built (deliberately, nothing in the frontend needs it yet)

Refresh tokens / password reset / lock screen; invoice line items, refunds, taxes, QR payments; follow-up contact-attempt history; doctor working hours / availability; prescription draft/issued status; patient merge; multiple doctors beyond the data model (the schema is ready, the UI assumes one); real WhatsApp/SMS sending (settings are stored, nothing is sent); object storage for files (bytea is used); a shared rate-limit store for several server instances.
