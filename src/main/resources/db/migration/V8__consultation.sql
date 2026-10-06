-- V8: consultation (exactly one per visit) and everything the doctor records in the consultation room,
-- plus reusable prescription templates.
-- A consultation is keyed to the VISIT, never to the token: tokens repeat every day.

create table consultation (
    id         bigint generated always as identity primary key,
    clinic_id  bigint not null references clinic(id),
    visit_id   bigint not null unique references visit(id),
    patient_id bigint not null references patient(id),
    doctor_id  bigint not null references app_user(id),
    consult_no bigint not null,                 -- display "C00001"
    notes      text not null default '',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (clinic_id, consult_no)
);
create index ix_consultation_patient on consultation (patient_id, created_at desc);
create index ix_consultation_doctor  on consultation (doctor_id, created_at desc);

-- Names are snapshotted so history never changes when a catalog entry is later edited.
create table consultation_symptom (
    consultation_id bigint not null references consultation(id) on delete cascade,
    position        int not null,
    name            text not null,
    catalog_id      bigint references symptom_catalog(id),
    primary key (consultation_id, position)
);

create table consultation_diagnosis (
    consultation_id bigint not null references consultation(id) on delete cascade,
    position        int not null,
    name            text not null,
    catalog_id      bigint references diagnosis_catalog(id),
    primary key (consultation_id, position)
);
create index ix_consultation_diagnosis_name on consultation_diagnosis (lower(name));

create table consultation_examination (
    id              bigint generated always as identity primary key,
    consultation_id bigint not null references consultation(id) on delete cascade,
    category        text not null,
    finding         text not null,
    custom_finding  text
);
create index ix_consultation_exam on consultation_examination (consultation_id);

create table consultation_investigation (
    id              bigint generated always as identity primary key,
    consultation_id bigint not null references consultation(id) on delete cascade,
    catalog_id      bigint references investigation_catalog(id),
    name            text not null,
    is_custom       boolean not null default false,
    status          text not null default 'Ordered' check (status in ('Ordered','Completed')),
    result_note     text
);
create index ix_consultation_inv on consultation_investigation (consultation_id);

create table prescription_item (
    id              bigint generated always as identity primary key,
    consultation_id bigint not null references consultation(id) on delete cascade,
    position        int not null,
    medicine_name   text not null,              -- snapshot, never re-synced from the master
    product_id      bigint references medicine_product(id),     -- traceability only
    frequency       text,                       -- code such as '1-0-1', 'SOS'
    duration        text,                       -- '5 Days'
    timing          text,                       -- meal timing (frontend field "instructions")
    notes           text
);
create index ix_rx_consultation on prescription_item (consultation_id, position);
create index ix_rx_product      on prescription_item (product_id) where product_id is not null;

create table prescription_template (
    id        bigint generated always as identity primary key,
    clinic_id bigint not null references clinic(id),
    doctor_id bigint not null references app_user(id),
    name      text not null,
    unique (doctor_id, name)
);

create table prescription_template_item (
    id            bigint generated always as identity primary key,
    template_id   bigint not null references prescription_template(id) on delete cascade,
    position      int not null,
    medicine_name text not null,
    product_id    bigint references medicine_product(id),
    frequency     text,
    duration      text,
    timing        text,
    notes         text
);
create index ix_rx_template_item on prescription_template_item (template_id, position);
