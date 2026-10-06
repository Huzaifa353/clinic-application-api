-- V1: clinic, users, doctor identity, uploaded files.
-- Conventions for the whole schema:
--   * bigint identity PKs; human-readable numbers (P00001, INV-00001, REC000001, token 01) are stored
--     as integers and formatted by the API.
--   * Enums are text + CHECK (easy to migrate).  Money = numeric(12,2) PKR.  Timestamps = timestamptz.
--   * "Day" boundaries use the clinic's time zone (clinic.timezone), computed in the application.
--   * clinic_id is on every tenant-owned table so multi-clinic is possible later (today: always one clinic).

create extension if not exists pg_trgm;   -- fuzzy / substring search on names

create table clinic (
    id         bigint generated always as identity primary key,
    name       text not null,
    timezone   text not null default 'Asia/Karachi',
    created_at timestamptz not null default now()
);

create table app_user (
    id            bigint generated always as identity primary key,
    clinic_id     bigint not null references clinic(id),
    email         text not null,
    password_hash text not null,
    full_name     text not null,
    role          text not null check (role in ('doctor','assistant')),
    active        boolean not null default true,
    created_at    timestamptz not null default now()
);
create unique index ux_app_user_email on app_user (lower(email));
create index ix_app_user_clinic on app_user (clinic_id);

-- Professional identity printed on documents (1:1 with a doctor user).
create table doctor_profile (
    user_id         bigint primary key references app_user(id),
    qualifications  text,
    specialization  text,
    registration_no text,                       -- PMDC / PMC number
    address_line1   text,
    address_line2   text,
    city            text,
    state           text,
    zip             text,
    country         text
);

-- Manual "away" flag. "Consulting" is derived from visit.status, not stored here.
create table doctor_status (
    user_id    bigint primary key references app_user(id),
    away       boolean not null default false,
    updated_at timestamptz not null default now()
);

-- Uploaded binaries: logo, signature, favicon, patient documents. bytea is fine at clinic scale;
-- move to object storage later by filling storage_key and nulling data.
create table file_asset (
    id           bigint generated always as identity primary key,
    clinic_id    bigint not null references clinic(id),
    file_name    text not null,
    content_type text not null,
    size_bytes   bigint not null check (size_bytes >= 0),
    data         bytea,
    storage_key  text,
    created_by   bigint references app_user(id),
    created_at   timestamptz not null default now()
);
create index ix_file_asset_clinic on file_asset (clinic_id);
