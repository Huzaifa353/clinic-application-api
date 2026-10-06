-- V4: appointments.
-- A reschedule keeps the original row (status 'rescheduled') and creates a new row pointing back via
-- rescheduled_from_id; "rescheduled to" is the reverse lookup.

create table appointment (
    id                  bigint generated always as identity primary key,
    clinic_id           bigint not null references clinic(id),
    appointment_no      bigint not null,                -- display "A00001"
    patient_id          bigint not null references patient(id),
    doctor_id           bigint not null references app_user(id),
    appt_date           date not null,
    appt_time           time not null,
    type                text check (type in ('Consultation','Follow-up','New Patient','Review')),
    status              text not null default 'scheduled'
                        check (status in ('scheduled','arrived','checked-in','cancelled','no-show','rescheduled')),
    notes               text,
    cancellation_reason text,
    rescheduled_from_id bigint references appointment(id),
    reminder_status     text check (reminder_status in ('pending','sent')),   -- future WhatsApp/SMS hook
    created_by          bigint references app_user(id),
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    unique (clinic_id, appointment_no)
);

-- A doctor slot can't be double-booked while the appointment is still open.
create unique index ux_appt_doctor_slot on appointment (doctor_id, appt_date, appt_time)
    where status in ('scheduled','arrived','checked-in');
create index ix_appt_date    on appointment (clinic_id, appt_date);
create index ix_appt_patient on appointment (patient_id, appt_date desc);
create index ix_appt_resched on appointment (rescheduled_from_id) where rescheduled_from_id is not null;
