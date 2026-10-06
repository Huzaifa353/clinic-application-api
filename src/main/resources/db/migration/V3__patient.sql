-- V3: patients.
-- total visits / last visit / outstanding balance are NOT stored here; they are derived
-- (see views patient_visit_stats in V5 and patient_outstanding in V10) so they can't drift.

create table patient (
    id                  bigint generated always as identity primary key,
    clinic_id           bigint not null references clinic(id),
    patient_no          bigint not null,                -- display "P00001"
    name                text not null,
    mobile              text not null,                  -- as entered
    mobile_norm         text not null,                  -- 03XXXXXXXXX; deliberately NOT unique (households share a number)
    gender              text check (gender in ('Male','Female','Other')),
    date_of_birth       date,
    age_years           smallint check (age_years between 0 and 150),   -- fallback when DOB is unknown
    cnic                text,
    blood_group         text,
    address             text,
    photo_file_id       bigint references file_asset(id),
    -- Editable clinical lists, replaced as a whole from the consultation side panel.
    allergies           text[] not null default '{}',
    medical_history     text[] not null default '{}',
    surgical_history    text[] not null default '{}',
    current_medications text[] not null default '{}',
    registered_at       timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    created_by          bigint references app_user(id),
    unique (clinic_id, patient_no)
);
create index ix_patient_mobile    on patient (clinic_id, mobile_norm);
create index ix_patient_cnic      on patient (clinic_id, cnic) where cnic is not null;
create index ix_patient_name_trgm on patient using gin (lower(name) gin_trgm_ops);
