-- V9: follow-ups (one optional plan per consultation).
-- Upcoming / Due today / Overdue are DERIVED from the effective due date vs the clinic-local today;
-- only the explicit outcome (completed / cancelled) is stored. A follow-up REFERENCES an appointment,
-- it is not one.

create table follow_up (
    id                    bigint generated always as identity primary key,
    clinic_id             bigint not null references clinic(id),
    consultation_id       bigint not null unique references consultation(id) on delete cascade,
    patient_id            bigint not null references patient(id),
    days                  int not null check (days >= 0),
    reason                text,
    due_date              date not null,                -- visit date + days, stored so it can be indexed
    override_due_date     date,                         -- set by "reschedule"; effective = coalesce(override, due)
    status                text check (status in ('completed','cancelled')),   -- null = still open
    contact_status        text check (contact_status in ('contacted','no-response','declined')),
    last_reminded_on      date,
    linked_appointment_id bigint references appointment(id),
    cancellation_reason   text,
    resolved_at           timestamptz
);
create index ix_followup_open on follow_up (clinic_id, (coalesce(override_due_date, due_date))) where status is null;
create index ix_followup_patient on follow_up (patient_id);
create index ix_followup_appt    on follow_up (linked_appointment_id) where linked_appointment_id is not null;
