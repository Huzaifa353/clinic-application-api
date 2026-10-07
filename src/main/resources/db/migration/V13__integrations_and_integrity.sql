-- V13: gaps found in the coverage audit of V1-V12 against the frontend.
--   1. Settings -> WhatsApp and Settings -> Payment (JazzCash / EasyPaisa) pages exist in the frontend
--      with credential fields but had no table.
--   2. Per-doctor preferred print language (TO_DO_v4 section 15 priority: document > doctor > clinic > 'en').
--   3. Parent/child rows could disagree about WHICH PATIENT they belong to (e.g. a consultation of patient 5
--      attached to a visit of patient 9). Composite foreign keys now make that impossible.
--   4. Money rules that were only "application rules" are now enforced by the database as well:
--      no overpayment, no payment on a voided invoice, no lowering an invoice below what was paid,
--      invoices are voided never deleted, a void can't be undone.
--   5. A zero-total invoice (waived follow-up) is 'Paid', not 'Unpaid'.

-- 1. Third-party integration settings. *_enc columns hold values ENCRYPTED BY THE APPLICATION before
--    insert; the API must never return them (only "is set" flags).
create table integration_settings (
    clinic_id                     bigint primary key references clinic(id),
    whatsapp_enabled              boolean not null default false,
    whatsapp_phone_number_id      text,
    whatsapp_business_account_id  text,
    whatsapp_access_token_enc     text,
    whatsapp_webhook_token_enc    text,
    jazzcash_enabled              boolean not null default false,
    jazzcash_merchant_id          text,
    jazzcash_password_enc         text,
    jazzcash_integrity_salt_enc   text,
    easypaisa_enabled             boolean not null default false,
    easypaisa_store_id            text,
    easypaisa_account_number      text,
    easypaisa_api_key_enc         text,
    updated_at                    timestamptz not null default now()
);
insert into integration_settings (clinic_id) select id from clinic;

-- 2. Doctor print-language preference (null = fall back to clinic default).
alter table doctor_profile
    add column preferred_print_language text
    check (preferred_print_language in ('en','ur','bilingual'));

-- 3. Same-patient guarantees via composite foreign keys (MATCH SIMPLE: a null parent id is not checked).
alter table appointment  add constraint uq_appointment_id_patient  unique (id, patient_id);
alter table visit        add constraint uq_visit_id_patient        unique (id, patient_id);
alter table consultation add constraint uq_consultation_id_patient unique (id, patient_id);

alter table visit        add constraint fk_visit_appointment_patient
    foreign key (appointment_id, patient_id) references appointment (id, patient_id);
alter table consultation add constraint fk_consultation_visit_patient
    foreign key (visit_id, patient_id) references visit (id, patient_id);
alter table follow_up    add constraint fk_followup_consultation_patient
    foreign key (consultation_id, patient_id) references consultation (id, patient_id);
alter table follow_up    add constraint fk_followup_appointment_patient
    foreign key (linked_appointment_id, patient_id) references appointment (id, patient_id);
alter table invoice      add constraint fk_invoice_visit_patient
    foreign key (visit_id, patient_id) references visit (id, patient_id);

-- 4a. A payment may only be added to an open invoice and may not exceed the outstanding balance.
--     The invoice row is locked first, so two concurrent payments can't both squeeze through.
create function payment_guard() returns trigger language plpgsql as $$
declare
    inv   invoice%rowtype;
    paid  numeric(12,2);
begin
    select * into inv from invoice where id = new.invoice_id for update;
    if inv.voided then
        raise exception 'invoice % is voided; no payments accepted', inv.invoice_no;
    end if;
    select coalesce(sum(amount), 0) into paid from payment where invoice_id = new.invoice_id;
    if paid + new.amount > inv.total then
        raise exception 'payment of % exceeds outstanding balance % on invoice %',
            new.amount, inv.total - paid, inv.invoice_no;
    end if;
    return new;
end $$;
create trigger trg_payment_guard before insert on payment
    for each row execute function payment_guard();

-- 4b. Invoice guards: never below what's been paid, never deleted, void is one-way.
create function invoice_guard() returns trigger language plpgsql as $$
declare
    paid numeric(12,2);
begin
    if tg_op = 'DELETE' then
        raise exception 'invoices are voided, never deleted (invoice %)', old.invoice_no;
    end if;
    if old.voided and not new.voided then
        raise exception 'a voided invoice cannot be re-opened (invoice %)', old.invoice_no;
    end if;
    if new.total <> old.total and not new.voided then
        select coalesce(sum(amount), 0) into paid from payment where invoice_id = new.id;
        if new.total < paid then
            raise exception 'invoice total % is below the amount already paid %', new.total, paid;
        end if;
    end if;
    return case when tg_op = 'DELETE' then old else new end;
end $$;
create trigger trg_invoice_guard before update or delete on invoice
    for each row execute function invoice_guard();

-- 5. Zero-total invoices are settled by definition.
create or replace view invoice_summary as
select i.*,
       coalesce(p.paid, 0)                              as paid,
       i.total - coalesce(p.paid, 0)                    as balance,
       case when i.voided                       then 'Void'
            when coalesce(p.paid, 0) >= i.total then 'Paid'
            when coalesce(p.paid, 0) <= 0       then 'Unpaid'
            else 'Partial' end                          as payment_status
from invoice i
left join (select invoice_id, sum(amount) as paid from payment group by invoice_id) p on p.invoice_id = i.id;
