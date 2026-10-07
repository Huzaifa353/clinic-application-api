-- V17: what the Medicine Master search needs.
--  * composition_ingredient.position keeps ingredients in the order they were entered
--    ("Paracetamol + Caffeine", not alphabetical).
--  * medicine_product.generic_name / strength_label are denormalised display + search values, kept in
--    step by the application whenever a composition changes. With them the prescription-box search is a
--    single indexed query over one table instead of aggregating compositions for every product on every
--    keystroke (the real Pakistani drug registry runs to tens of thousands of products).

alter table composition_ingredient add column position int not null default 0;

alter table medicine_product
    add column generic_name   text not null default '',
    add column strength_label text not null default '';

create index ix_medicine_generic_trgm on medicine_product using gin (lower(generic_name) gin_trgm_ops);
create index ix_medicine_visible      on medicine_product (clinic_id, status);
