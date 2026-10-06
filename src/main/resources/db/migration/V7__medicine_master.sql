-- V7: Medicine Master (Pakistan medicine database). Master data is global (clinic_id null) or
-- clinic-owned (quick "type it and go" entries made from the prescription box). Usage counts and
-- favourites are per clinic (clinic_medicine).

create table ingredient (
    id              bigint generated always as identity primary key,
    name            text not null,
    normalized_name text not null unique,
    aliases         text[] not null default '{}',
    status          text not null default 'Active'
);

create table manufacturer (
    id              bigint generated always as identity primary key,
    name            text not null,
    normalized_name text not null unique,
    country         text,
    status          text not null default 'Active'
);

create table brand (
    id              bigint generated always as identity primary key,
    manufacturer_id bigint not null references manufacturer(id),
    name            text not null,
    normalized_name text not null,
    aliases         text[] not null default '{}',
    status          text not null default 'Active',
    unique (manufacturer_id, normalized_name)
);

create table medicine_composition (
    id bigint generated always as identity primary key
);

-- One ingredient + strength within a composition; combination products have several rows.
create table composition_ingredient (
    composition_id bigint not null references medicine_composition(id) on delete cascade,
    ingredient_id  bigint not null references ingredient(id),
    strength_value numeric(12,3) not null,
    strength_unit  text not null,               -- mg, g, mL, IU, %, mcg
    per_value      numeric(12,3),               -- the "5" in "250mg / 5mL"
    per_unit       text,                        -- the "mL"
    primary key (composition_id, ingredient_id)
);

create table medicine_product (
    id                  bigint generated always as identity primary key,
    clinic_id           bigint references clinic(id),           -- null = global master record
    brand_id            bigint not null references brand(id),
    composition_id      bigint not null references medicine_composition(id),
    manufacturer_id     bigint not null references manufacturer(id),
    product_name        text not null,
    normalized_name     text not null,
    dosage_form         text not null,                          -- Tablet, Syrup, Injection ...
    route               text not null,                          -- Oral, IV, Topical ...
    status              text not null default 'Active'
                        check (status in ('Active','Inactive','Discontinued','Under Review','Unverified','Archived')),
    verification_status text not null default 'Unverified'
                        check (verification_status in ('Verified','Unverified','Needs Review')),
    category            text,                                   -- 'Manual Entry' for quick-adds
    registration_number text,
    registration_status text,
    aliases             text[] not null default '{}',
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);
create index ix_medicine_name_trgm on medicine_product using gin (normalized_name gin_trgm_ops);
create index ix_medicine_status    on medicine_product (status);
create index ix_medicine_brand     on medicine_product (brand_id);
create index ix_medicine_clinic    on medicine_product (clinic_id) where clinic_id is not null;

create table medicine_pack (
    id               bigint generated always as identity primary key,
    product_id       bigint not null references medicine_product(id) on delete cascade,
    quantity         numeric(10,2) not null,
    unit             text not null,
    pack_description text not null,                             -- "20 tablets", "60 mL bottle"
    status           text not null default 'Active'
);
create index ix_medicine_pack_product on medicine_pack (product_id);

-- Append-only price history per pack.
create table medicine_price (
    id             bigint generated always as identity primary key,
    pack_id        bigint not null references medicine_pack(id) on delete cascade,
    price          numeric(12,2) not null check (price >= 0),
    currency       text not null default 'PKR',
    effective_from date not null,
    source         text
);
create index ix_medicine_price_latest on medicine_price (pack_id, effective_from desc);

-- Per-clinic usage statistics and favourites (drives search ranking).
create table clinic_medicine (
    clinic_id    bigint not null references clinic(id),
    product_id   bigint not null references medicine_product(id),
    usage_count  int not null default 0,
    last_used_at timestamptz,
    is_favorite  boolean not null default false,
    primary key (clinic_id, product_id)
);
