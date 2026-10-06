-- V5: visit = the frontend's "queue entry". One row per attendance, kept forever.
-- "Today's queue" is simply queue_date = today (clinic-local); there is no daily reset, and queue
-- history comes for free. Fee/payment details live on the invoice (V10), not here.

create table visit (
    id                 bigint generated always as identity primary key,
    clinic_id          bigint not null references clinic(id),
    queue_date         date not null,
    token_no           int  not null check (token_no > 0),      -- display "01"; unique per day only
    patient_id         bigint not null references patient(id),
    doctor_id          bigint references app_user(id),          -- set when the doctor starts the consultation
    appointment_id     bigint references appointment(id),
    source             text not null check (source in ('walk-in','appointment')),
    status             text not null default 'waiting'
                       check (status in ('waiting','consulting','completed','hold','skipped','cancelled')),
    urgent             boolean not null default false,
    sort_order         int not null,                            -- drag-reorder position within the day
    -- Vitals taken at intake (all optional)
    bp_systolic        smallint,
    bp_diastolic       smallint,
    temperature_f      numeric(4,1),
    pulse              smallint,
    weight_kg          numeric(5,1),
    spo2               smallint,
    checked_in_at      timestamptz not null default now(),      -- QueueEntry.addedAt: FIFO basis + waiting time
    consult_started_at timestamptz,
    completed_at       timestamptz,
    created_by         bigint references app_user(id),
    updated_at         timestamptz not null default now(),
    unique (clinic_id, queue_date, token_no),
    check (status <> 'consulting' or doctor_id is not null),
    check (source <> 'appointment' or appointment_id is not null)
);

-- At most one patient "consulting" per doctor at a time (a frontend rule, now enforced by the DB).
create unique index ux_visit_one_consulting on visit (doctor_id) where status = 'consulting';
create index ix_visit_queue   on visit (clinic_id, queue_date, status, sort_order);
create index ix_visit_patient on visit (patient_id, queue_date desc);
create index ix_visit_appt    on visit (appointment_id) where appointment_id is not null;

-- Replaces the frontend's totalVisits / lastVisit fields.
create view patient_visit_stats as
select patient_id,
       count(*) filter (where status <> 'cancelled') as total_visits,
       max(queue_date) filter (where status <> 'cancelled') as last_visit
from visit
group by patient_id;
