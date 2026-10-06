-- V10: billing. invoice = what is owed; payment = immutable money received against it.
-- paid / balance / payment_status are DERIVED (invoice_summary view) so they can never drift.
-- Application rule: lock the invoice row (SELECT ... FOR UPDATE) before inserting a payment and reject
-- it if sum(payments) + amount > total or the invoice is voided.

create table service_fee (
    id         bigint generated always as identity primary key,
    clinic_id  bigint not null references clinic(id),
    name       text not null,
    fee        numeric(12,2) not null check (fee >= 0),
    active     boolean not null default true,
    sort_order int not null default 0
);
create index ix_service_fee_clinic on service_fee (clinic_id, sort_order);

create table invoice (
    id                 bigint generated always as identity primary key,
    clinic_id          bigint not null references clinic(id),
    invoice_no         bigint not null,                 -- display "INV-00001"
    patient_id         bigint not null references patient(id),
    visit_id           bigint references visit(id),     -- null for billing not tied to a visit
    doctor_id          bigint references app_user(id),
    service_id         bigint references service_fee(id),
    description        text,
    consultation_fee   numeric(12,2) not null default 0 check (consultation_fee >= 0),
    additional_charges numeric(12,2) not null default 0 check (additional_charges >= 0),
    discount           numeric(12,2) not null default 0 check (discount >= 0),
    total              numeric(12,2) not null check (total >= 0),
    issued_at          timestamptz not null default now(),
    voided             boolean not null default false,  -- void, never delete (audit trail)
    void_reason        text,
    voided_at          timestamptz,
    voided_by          bigint references app_user(id),
    created_by         bigint references app_user(id),
    unique (clinic_id, invoice_no),
    check (total = consultation_fee + additional_charges - discount),
    check (not voided or voided_at is not null)
);
create index ix_invoice_patient on invoice (patient_id, issued_at desc);
create index ix_invoice_issued  on invoice (clinic_id, issued_at);
create index ix_invoice_visit   on invoice (visit_id) where visit_id is not null;

create table payment (
    id          bigint generated always as identity primary key,
    clinic_id   bigint not null references clinic(id),
    receipt_no  bigint not null,                        -- display "REC000001"
    invoice_id  bigint not null references invoice(id),
    amount      numeric(12,2) not null check (amount > 0),
    method      text not null check (method in ('Cash','Card','Bank Transfer','EasyPaisa','JazzCash')),
    reference   text,
    paid_at     timestamptz not null default now(),
    received_by bigint references app_user(id),
    unique (clinic_id, receipt_no)
);
create index ix_payment_invoice on payment (invoice_id);
create index ix_payment_paid_at on payment (clinic_id, paid_at);

-- Payments are immutable: a correction is a new invoice/void, never an edit.
create function payment_immutable() returns trigger language plpgsql as $$
begin
    raise exception 'payment rows are immutable (id=%)', old.id;
end $$;
create trigger trg_payment_immutable before update or delete on payment
    for each row execute function payment_immutable();

create view invoice_summary as
select i.*,
       coalesce(p.paid, 0)                              as paid,
       i.total - coalesce(p.paid, 0)                    as balance,
       case when i.voided                      then 'Void'
            when coalesce(p.paid, 0) <= 0      then 'Unpaid'
            when coalesce(p.paid, 0) >= i.total then 'Paid'
            else 'Partial' end                          as payment_status
from invoice i
left join (select invoice_id, sum(amount) as paid from payment group by invoice_id) p on p.invoice_id = i.id;

-- Outstanding per patient (replaces the frontend's never-decremented outstandingBalance field).
create view patient_outstanding as
select patient_id, sum(balance) as outstanding
from invoice_summary
where not voided and balance > 0
group by patient_id;
