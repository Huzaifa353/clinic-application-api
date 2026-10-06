-- V11: patient documents, cross-role notifications, audit trail.

create table patient_document (
    id          bigint generated always as identity primary key,
    clinic_id   bigint not null references clinic(id),
    patient_id  bigint not null references patient(id),
    file_id     bigint references file_asset(id),       -- null while an upload is metadata-only
    file_name   text not null,
    doc_type    text not null check (doc_type in
                ('Lab Report','X-Ray','Ultrasound','Prescription','Referral','Other')),
    uploaded_by bigint references app_user(id),
    uploaded_at timestamptz not null default now()
);
create index ix_document_patient on patient_document (patient_id, uploaded_at desc);

-- Typed domain events shown to the other role (today: doctor started a consultation -> assistant).
create table notification (
    id          bigint generated always as identity primary key,
    clinic_id   bigint not null references clinic(id),
    type        text not null check (type in ('CONSULTATION_STARTED')),
    target_role text not null check (target_role in ('doctor','assistant')),
    visit_id    bigint references visit(id),
    message     text not null,
    created_at  timestamptz not null default now(),
    read_at     timestamptz
);
create index ix_notification_unread on notification (clinic_id, target_role, created_at desc) where read_at is null;
create index ix_notification_visit  on notification (visit_id) where visit_id is not null;

create table audit_log (
    id        bigint generated always as identity primary key,
    clinic_id bigint not null references clinic(id),
    user_id   bigint references app_user(id),
    action    text not null,                            -- 'invoice.void', 'visit.status', 'patient.update'
    entity    text not null,
    entity_id bigint,
    detail    jsonb,
    at        timestamptz not null default now()
);
create index ix_audit_entity on audit_log (entity, entity_id);
create index ix_audit_clinic on audit_log (clinic_id, at desc);
