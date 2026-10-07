package com.preclinic.backend.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.preclinic.backend.billing.InvoiceDtos.BillingSummary;
import com.preclinic.backend.billing.InvoiceDtos.InvoiceDto;
import com.preclinic.backend.billing.InvoiceDtos.MethodAmount;
import com.preclinic.backend.billing.InvoiceDtos.PaymentDto;
import com.preclinic.backend.billing.InvoiceDtos.ReceiptDto;
import com.preclinic.backend.billing.InvoiceDtos.ReceivePaymentRequest;
import com.preclinic.backend.common.ApiException;
import com.preclinic.backend.common.AuditService;
import com.preclinic.backend.common.ClinicClock;
import com.preclinic.backend.common.NumberSequenceService;
import com.preclinic.backend.common.PageResult;
import com.preclinic.backend.common.SequenceKind;
import com.preclinic.backend.event.EventBus;
import com.preclinic.backend.patient.PatientService;
import com.preclinic.backend.security.CurrentUser;
import com.preclinic.backend.user.DoctorDirectory;

/**
 * Invoices and the payments against them. The server owns every figure: totals are computed here, and
 * paid / balance / status come from the payments (view {@code invoice_summary}), so the screen can
 * never show a number that disagrees with the money actually received.
 */
@Service
public class InvoiceService {

	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

	private static final String SELECT = """
			select i.id, i.invoice_no, i.patient_id, p.name as patient_name, p.patient_no, p.mobile,
			       i.visit_id, v.token_no, i.doctor_id, d.full_name as doctor_name, i.service_id,
			       coalesce(i.description, sf.name, 'Consultation') as description, i.issued_at,
			       i.consultation_fee, i.additional_charges, i.discount, i.total, i.paid, i.balance,
			       i.payment_status, i.voided, i.void_reason, i.voided_at
			from invoice_summary i
			join patient p on p.id = i.patient_id
			left join app_user d on d.id = i.doctor_id
			left join service_fee sf on sf.id = i.service_id
			left join visit v on v.id = i.visit_id
			""";

	/** What a new invoice is made of; used by the walk-in billing endpoint and by queue intake. */
	public record NewInvoice(long patientId, Long visitId, Long doctorId, Long serviceId, String description,
			BigDecimal consultationFee, BigDecimal additionalCharges, BigDecimal discount, BigDecimal amountPaid,
			String paymentMethod, String reference) {
	}

	public record Query(LocalDate from, LocalDate to, String status, String method, Long doctorId, Long patientId,
			String q, String sort, String dir, int skip, int limit) {
	}

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ClinicClock clock;
	private final NumberSequenceService sequences;
	private final PatientService patients;
	private final DoctorDirectory doctors;
	private final AuditService audit;
	private final EventBus events;

	public InvoiceService(JdbcClient jdbc, CurrentUser currentUser, ClinicClock clock, NumberSequenceService sequences,
			PatientService patients, DoctorDirectory doctors, AuditService audit, EventBus events) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.clock = clock;
		this.sequences = sequences;
		this.patients = patients;
		this.doctors = doctors;
		this.audit = audit;
		this.events = events;
	}

	// --- reads ---------------------------------------------------------------------

	@Transactional(readOnly = true)
	public InvoiceDto get(long id) {
		InvoiceDto invoice = jdbc.sql(SELECT + " where i.id = :id and i.clinic_id = :c")
				.param("id", id).param("c", currentUser.clinicId()).query(mapper()).optional()
				.orElseThrow(() -> ApiException.notFound("INVOICE_NOT_FOUND", "No such invoice."));
		return withPayments(List.of(invoice)).get(0);
	}

	@Transactional(readOnly = true)
	public PageResult<InvoiceDto> list(Query q) {
		Map<String, Object> params = new HashMap<>();
		params.put("c", currentUser.clinicId());
		params.put("tz", clock.zone().getId());
		StringBuilder where = new StringBuilder(" where i.clinic_id = :c");
		if (q.from() != null) {
			where.append(" and (i.issued_at at time zone :tz)::date >= :from");
			params.put("from", q.from());
		}
		if (q.to() != null) {
			where.append(" and (i.issued_at at time zone :tz)::date <= :to");
			params.put("to", q.to());
		}
		if (q.status() != null && !q.status().isBlank()) {
			where.append(" and i.payment_status = :status");
			params.put("status", "Cancelled".equalsIgnoreCase(q.status()) ? "Void" : q.status());
		}
		if (q.method() != null && !q.method().isBlank()) {
			where.append(" and exists (select 1 from payment pm where pm.invoice_id = i.id and pm.method = :method)");
			params.put("method", q.method());
		}
		if (q.doctorId() != null) {
			where.append(" and i.doctor_id = :doctorId");
			params.put("doctorId", q.doctorId());
		}
		if (q.patientId() != null) {
			where.append(" and i.patient_id = :patientId");
			params.put("patientId", q.patientId());
		}
		if (q.q() != null && !q.q().isBlank()) {
			where.append(" and (lower(p.name) like :text escape '\\'")
					.append(" or p.mobile_norm like :text escape '\\'")
					.append(" or lower('p' || lpad(p.patient_no::text, 5, '0')) like :text escape '\\'")
					.append(" or lower('inv-' || lpad(i.invoice_no::text, 5, '0')) like :text escape '\\'")
					.append(" or lower(d.full_name) like :text escape '\\'")
					.append(" or lower(coalesce(i.description, sf.name, 'Consultation')) like :text escape '\\'")
					.append(" or exists (select 1 from payment pm where pm.invoice_id = i.id")
					.append("   and (lower('rec' || lpad(pm.receipt_no::text, 6, '0')) like :text escape '\\'")
					.append("        or lower(coalesce(pm.reference, '')) like :text escape '\\')))");
			params.put("text", "%" + q.q().trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%")
					.replace("_", "\\_") + "%");
		}
		String from = """
				from invoice_summary i
				join patient p on p.id = i.patient_id
				left join app_user d on d.id = i.doctor_id
				left join service_fee sf on sf.id = i.service_id
				""";
		long total = jdbc.sql("select count(*) " + from + where).params(params).query(Long.class).single();
		params.put("limit", q.limit());
		params.put("skip", q.skip());
		List<InvoiceDto> page = jdbc.sql(SELECT + where + " order by " + orderBy(q) + " limit :limit offset :skip")
				.params(params).query(mapper()).list();
		return new PageResult<>(withPayments(page), total);
	}

	/** Whitelisted ORDER BY: input only ever picks one of these constants. */
	private static String orderBy(Query q) {
		boolean asc = "asc".equalsIgnoreCase(q.dir());
		String dir = asc ? " asc" : " desc";
		String key = q.sort() == null ? "issuedAt" : q.sort();
		return switch (key) {
			case "issuedAt" -> "i.issued_at" + dir + ", i.id" + dir;
			case "total" -> "i.total" + dir + ", i.id";
			case "balance" -> "i.balance" + dir + ", i.id";
			case "patient" -> "lower(p.name)" + dir + ", i.id";
			case "invoiceNo" -> "i.invoice_no" + dir;
			default -> throw ApiException.badRequest("INVALID_SORT", "Unknown sort: " + key);
		};
	}

	/** Collection / billed / pending for the period (clinic-local dates, inclusive); outstanding is current. */
	@Transactional(readOnly = true)
	public BillingSummary summary(LocalDate from, LocalDate to, Long doctorId) {
		LocalDate today = clock.today();
		Map<String, Object> params = new HashMap<>();
		params.put("c", currentUser.clinicId());
		params.put("tz", clock.zone().getId());
		params.put("from", from != null ? from : today);
		params.put("to", to != null ? to : today);
		String doctorFilter = "";
		if (doctorId != null) {
			doctorFilter = " and i.doctor_id = :doctorId";
			params.put("doctorId", doctorId);
		}
		List<MethodAmount> byMethod = jdbc.sql("""
				select pm.method, sum(pm.amount) as amount
				from payment pm join invoice i on i.id = pm.invoice_id
				where pm.clinic_id = :c and not i.voided
				  and (pm.paid_at at time zone :tz)::date between :from and :to""" + doctorFilter + """

				group by pm.method order by sum(pm.amount) desc
				""").params(params).query(MethodAmount.class).list();
		BigDecimal collection = byMethod.stream().map(MethodAmount::amount).reduce(ZERO, BigDecimal::add);

		var period = jdbc.sql("""
				select coalesce(sum(i.total), 0) as billed, count(*) as invoice_count,
				       count(*) filter (where i.payment_status <> 'Paid') as pending_count
				from invoice_summary i
				where i.clinic_id = :c and not i.voided
				  and (i.issued_at at time zone :tz)::date between :from and :to""" + doctorFilter)
				.params(params).query((rs, n) -> new Object[] { rs.getBigDecimal("billed"), rs.getLong("invoice_count"),
						rs.getLong("pending_count") }).single();
		BigDecimal outstanding = jdbc.sql("""
				select coalesce(sum(i.balance), 0) from invoice_summary i
				where i.clinic_id = :c and not i.voided and i.balance > 0""" + doctorFilter)
				.params(params).query(BigDecimal.class).single();
		return new BillingSummary(collection, (BigDecimal) period[0], outstanding, (Long) period[1], (Long) period[2],
				byMethod);
	}

	// --- writes --------------------------------------------------------------------

	/** Creates an invoice (and the first payment when money was taken) and returns its id. */
	@Transactional
	public long create(NewInvoice n) {
		long clinicId = currentUser.clinicId();
		patients.requireExists(n.patientId());
		if (n.visitId() != null) {
			Integer match = jdbc.sql("select 1 from visit where id = :v and patient_id = :p and clinic_id = :c")
					.param("v", n.visitId()).param("p", n.patientId()).param("c", clinicId)
					.query(Integer.class).optional().orElse(null);
			if (match == null) {
				throw ApiException.unprocessable("VISIT_PATIENT_MISMATCH", "That visit does not belong to this patient.");
			}
		}
		String serviceName = null;
		BigDecimal serviceFee = null;
		if (n.serviceId() != null) {
			var service = jdbc.sql("select name, fee from service_fee where id = :id and clinic_id = :c and active")
					.param("id", n.serviceId()).param("c", clinicId)
					.query((rs, i) -> new Object[] { rs.getString("name"), rs.getBigDecimal("fee") }).optional()
					.orElseThrow(() -> ApiException.notFound("SERVICE_NOT_FOUND", "No such service."));
			serviceName = (String) service[0];
			serviceFee = (BigDecimal) service[1];
		}
		BigDecimal fee = money(n.consultationFee() != null ? n.consultationFee() : serviceFee);
		BigDecimal charges = money(n.additionalCharges());
		BigDecimal discount = money(n.discount());
		BigDecimal gross = fee.add(charges);
		if (discount.compareTo(gross) > 0) {
			throw ApiException.unprocessable("DISCOUNT_EXCEEDS_TOTAL", "The discount cannot be more than the charges.");
		}
		BigDecimal total = gross.subtract(discount);
		BigDecimal paidNow = money(n.amountPaid());
		if (paidNow.compareTo(total) > 0) {
			throw ApiException.unprocessable("PAYMENT_EXCEEDS_BALANCE",
					"The amount paid (" + paidNow + ") is more than the total (" + total + ").");
		}
		long doctorId = doctors.resolve(n.doctorId()).getId();
		String description = n.description() != null && !n.description().isBlank() ? n.description().trim() : serviceName;

		long invoiceNo = sequences.next(clinicId, SequenceKind.INVOICE);
		long id = jdbc.sql("""
				insert into invoice (clinic_id, invoice_no, patient_id, visit_id, doctor_id, service_id, description,
				                     consultation_fee, additional_charges, discount, total, created_by)
				values (:c, :no, :p, :v, :d, :s, :desc, :fee, :charges, :discount, :total, :user)
				returning id
				""")
				.param("c", clinicId).param("no", invoiceNo).param("p", n.patientId()).param("v", n.visitId())
				.param("d", doctorId).param("s", n.serviceId()).param("desc", description)
				.param("fee", fee).param("charges", charges).param("discount", discount).param("total", total)
				.param("user", currentUser.id()).query(Long.class).single();
		if (paidNow.signum() > 0) {
			insertPayment(id, paidNow, n.paymentMethod() == null ? "Cash" : n.paymentMethod(), n.reference());
		}
		audit.log("invoice.create", "invoice", id, AuditService.detail("invoiceNo", invoiceNo, "total", total,
				"paidAtCreation", paidNow, "patientId", n.patientId()));
		events.publish(clinicId, EventBus.Type.INVOICE);
		return id;
	}

	@Transactional
	public ReceiptDto receivePayment(long invoiceId, ReceivePaymentRequest r) {
		var invoice = lockInvoice(invoiceId);
		if (invoice.voided()) {
			throw ApiException.unprocessable("INVOICE_VOID", "This invoice has been voided; no payments can be taken.");
		}
		BigDecimal paid = jdbc.sql("select coalesce(sum(amount), 0) from payment where invoice_id = :id")
				.param("id", invoiceId).query(BigDecimal.class).single();
		BigDecimal balance = invoice.total().subtract(paid);
		if (balance.signum() <= 0) {
			throw ApiException.unprocessable("INVOICE_SETTLED", "This invoice is already fully paid.");
		}
		BigDecimal amount = money(r.amount());
		if (amount.compareTo(balance) > 0) {
			throw ApiException.unprocessable("PAYMENT_EXCEEDS_BALANCE",
					"The payment (" + amount + ") is more than the outstanding balance (" + balance + ").");
		}
		long paymentId = insertPayment(invoiceId, amount, r.method(), r.reference());
		audit.log("payment.receive", "invoice", invoiceId, AuditService.detail("paymentId", paymentId, "amount", amount,
				"method", r.method(), "balanceAfter", balance.subtract(amount)));
		events.publish(currentUser.clinicId(), EventBus.Type.INVOICE);
		InvoiceDto updated = get(invoiceId);
		PaymentDto payment = updated.payments().stream().filter(p -> p.id() == paymentId).findFirst().orElseThrow();
		return new ReceiptDto(payment, updated);
	}

	/** Voiding keeps the invoice and its payments for the audit trail; it just stops counting as money owed or collected. */
	@Transactional
	public InvoiceDto voidInvoice(long invoiceId, String reason) {
		var invoice = lockInvoice(invoiceId);
		if (invoice.voided()) {
			throw ApiException.unprocessable("INVOICE_ALREADY_VOID", "This invoice has already been voided.");
		}
		jdbc.sql("""
				update invoice set voided = true, void_reason = :reason, voided_at = now(), voided_by = :user
				where id = :id
				""").param("id", invoiceId).param("reason", reason == null || reason.isBlank() ? null : reason.trim())
				.param("user", currentUser.id()).update();
		audit.log("invoice.void", "invoice", invoiceId,
				AuditService.detail("invoiceNo", invoice.invoiceNo(), "reason", reason));
		events.publish(currentUser.clinicId(), EventBus.Type.INVOICE);
		return get(invoiceId);
	}

	// --- helpers -------------------------------------------------------------------

	private record Locked(long invoiceNo, BigDecimal total, boolean voided) {
	}

	/** Row lock: two cashiers paying the same invoice at once are serialised, so neither can overpay it. */
	private Locked lockInvoice(long id) {
		return jdbc.sql("select invoice_no, total, voided from invoice where id = :id and clinic_id = :c for update")
				.param("id", id).param("c", currentUser.clinicId())
				.query((rs, i) -> new Locked(rs.getLong("invoice_no"), rs.getBigDecimal("total"), rs.getBoolean("voided")))
				.optional().orElseThrow(() -> ApiException.notFound("INVOICE_NOT_FOUND", "No such invoice."));
	}

	private long insertPayment(long invoiceId, BigDecimal amount, String method, String reference) {
		long clinicId = currentUser.clinicId();
		long receiptNo = sequences.next(clinicId, SequenceKind.RECEIPT);
		return jdbc.sql("""
				insert into payment (clinic_id, receipt_no, invoice_id, amount, method, reference, received_by)
				values (:c, :no, :inv, :amount, :method, :ref, :user)
				returning id
				""")
				.param("c", clinicId).param("no", receiptNo).param("inv", invoiceId).param("amount", amount)
				.param("method", method).param("ref", reference == null || reference.isBlank() ? null : reference.trim())
				.param("user", currentUser.id()).query(Long.class).single();
	}

	private static BigDecimal money(BigDecimal value) {
		return (value == null ? BigDecimal.ZERO : value).setScale(2, java.math.RoundingMode.HALF_UP);
	}

	private List<InvoiceDto> withPayments(List<InvoiceDto> invoices) {
		if (invoices.isEmpty()) {
			return invoices;
		}
		List<Long> ids = invoices.stream().map(InvoiceDto::id).toList();
		Map<Long, List<PaymentDto>> byInvoice = new LinkedHashMap<>();
		jdbc.sql("""
				select pm.invoice_id, pm.id, pm.receipt_no, pm.amount, pm.method, pm.reference, pm.paid_at,
				       u.full_name as received_by
				from payment pm left join app_user u on u.id = pm.received_by
				where pm.invoice_id in (:ids) order by pm.paid_at, pm.id
				""").param("ids", ids)
				.query((rs, i) -> Map.entry(rs.getLong("invoice_id"), new PaymentDto(rs.getLong("id"),
						rs.getLong("receipt_no"), "REC" + String.format("%06d", rs.getLong("receipt_no")),
						rs.getBigDecimal("amount"), rs.getString("method"), rs.getString("reference"),
						rs.getObject("paid_at", OffsetDateTime.class).toInstant(), rs.getString("received_by"))))
				.list().forEach(e -> byInvoice.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
		return invoices.stream().map(inv -> new InvoiceDto(inv.id(), inv.invoiceNo(), inv.displayId(), inv.patientId(),
				inv.patientName(), inv.patientDisplayId(), inv.mobile(), inv.visitId(), inv.tokenNo(), inv.doctorId(),
				inv.doctorName(), inv.serviceId(), inv.description(), inv.issuedAt(), inv.consultationFee(),
				inv.additionalCharges(), inv.discount(), inv.total(), inv.paid(), inv.balance(), inv.paymentStatus(),
				inv.voided(), inv.voidReason(), inv.voidedAt(), byInvoice.getOrDefault(inv.id(), List.of()))).toList();
	}

	private RowMapper<InvoiceDto> mapper() {
		return (rs, i) -> {
			long no = rs.getLong("invoice_no");
			long patientNo = rs.getLong("patient_no");
			long visitId = rs.getLong("visit_id");
			boolean hasVisit = !rs.wasNull();
			int token = rs.getInt("token_no");
			long doctorId = rs.getLong("doctor_id");
			boolean hasDoctor = !rs.wasNull();
			long serviceId = rs.getLong("service_id");
			boolean hasService = !rs.wasNull();
			OffsetDateTime voidedAt = rs.getObject("voided_at", OffsetDateTime.class);
			return new InvoiceDto(rs.getLong("id"), no, "INV-" + String.format("%05d", no), rs.getLong("patient_id"),
					rs.getString("patient_name"), "P" + String.format("%05d", patientNo), rs.getString("mobile"),
					hasVisit ? visitId : null, hasVisit ? String.format("%02d", token) : null,
					hasDoctor ? doctorId : null, rs.getString("doctor_name"), hasService ? serviceId : null,
					rs.getString("description"), rs.getObject("issued_at", OffsetDateTime.class).toInstant(),
					rs.getBigDecimal("consultation_fee"), rs.getBigDecimal("additional_charges"),
					rs.getBigDecimal("discount"), rs.getBigDecimal("total"), rs.getBigDecimal("paid"),
					rs.getBigDecimal("balance"), rs.getString("payment_status"), rs.getBoolean("voided"),
					rs.getString("void_reason"), voidedAt == null ? null : voidedAt.toInstant(), List.of());
		};
	}
}
