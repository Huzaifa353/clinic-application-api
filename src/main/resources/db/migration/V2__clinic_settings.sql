-- V2: per-clinic settings (printing/localization/theme), Urdu dictionary, atomic number counters.

create table clinic_settings (
    clinic_id              bigint primary key references clinic(id),
    -- Printing & documents
    print_clinic_name      text not null,
    print_address          text,
    print_phone            text,
    print_whatsapp         text,
    print_email            text,
    print_website          text,
    logo_file_id           bigint references file_asset(id),
    signature_file_id      bigint references file_asset(id),
    show_signature         boolean not null default false,
    header_layout          text not null default 'classic'
                           check (header_layout in ('classic','doctor-focused','clinic-focused')),
    footer_text            text,
    footer_show_disclaimer boolean not null default false,
    default_language       text not null default 'en' check (default_language in ('en','ur','bilingual')),
    -- Localization
    date_format            text not null default 'DD-MM-YYYY',
    time_format            text not null default '24 Hours',
    currency_symbol        text not null default 'Rs.',
    -- Theme
    website_name           text not null default 'Clinstra',
    theme_logo_file_id     bigint references file_asset(id),
    theme_favicon_file_id  bigint references file_asset(id),
    updated_at             timestamptz not null default now()
);

-- Urdu dictionary used only for printed documents. Text with no entry prints as typed.
create table translation (
    id          bigint generated always as identity primary key,
    clinic_id   bigint not null references clinic(id),
    category    text not null check (category in
                ('symptom','diagnosis','investigation','examination','frequency','duration','instruction','medicine')),
    source_norm text not null,                  -- lower-cased, whitespace-collapsed English
    source_text text not null,
    urdu_text   text not null,
    unique (clinic_id, category, source_norm)
);

-- Atomic human-readable counters. scope = '' for lifetime counters (patient, invoice, receipt, ...)
-- and the clinic-local date 'YYYY-MM-DD' for the daily queue token.
--   insert into number_sequence (clinic_id, kind, scope, last_value) values (?, ?, ?, 1)
--   on conflict (clinic_id, kind, scope) do update set last_value = number_sequence.last_value + 1
--   returning last_value;
create table number_sequence (
    clinic_id  bigint not null references clinic(id),
    kind       text not null check (kind in
               ('patient','appointment','token','consultation','invoice','receipt')),
    scope      text not null default '',
    last_value bigint not null default 0,
    primary key (clinic_id, kind, scope)
);
