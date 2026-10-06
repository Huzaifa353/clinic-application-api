-- V6: suggestion catalogs used by the consultation room. The clinic can add to symptoms/diagnoses
-- on the fly; consultations store snapshots of the names, so editing a catalog never rewrites history.

create table symptom_catalog (
    id        bigint generated always as identity primary key,
    clinic_id bigint not null references clinic(id),
    name      text not null
);
create unique index ux_symptom_catalog on symptom_catalog (clinic_id, lower(name));

create table diagnosis_catalog (
    id        bigint generated always as identity primary key,
    clinic_id bigint not null references clinic(id),
    name      text not null,
    icd_code  text
);
create unique index ux_diagnosis_catalog on diagnosis_catalog (clinic_id, lower(name));

create table investigation_catalog (
    id        bigint generated always as identity primary key,
    clinic_id bigint not null references clinic(id),
    code      text not null,                    -- 'cbc', 'xray-chest' (frontend investigationId)
    name      text not null,
    full_name text not null,
    grp       text not null check (grp in ('Laboratory','Imaging','Other')),
    active    boolean not null default true,
    unique (clinic_id, code)
);

create table examination_finding_catalog (
    id         bigint generated always as identity primary key,
    clinic_id  bigint not null references clinic(id),
    category   text not null,                   -- General, ENT, Abdomen ...
    finding    text not null,
    sort_order int not null default 0
);
create unique index ux_exam_finding_catalog on examination_finding_catalog (clinic_id, category, lower(finding));

-- Dosage frequency codes (1-0-1, SOS, HS ...) with their English meaning.
create table frequency_code (
    id         bigint generated always as identity primary key,
    clinic_id  bigint not null references clinic(id),
    code       text not null,
    meaning_en text not null,
    sort_order int not null default 0,
    unique (clinic_id, code)
);

-- Duration ("5 Days") and meal-timing ("After Meals") option lists.
create table prescription_option (
    id         bigint generated always as identity primary key,
    clinic_id  bigint not null references clinic(id),
    kind       text not null check (kind in ('duration','timing')),
    value      text not null,
    sort_order int not null default 0,
    unique (clinic_id, kind, value)
);
