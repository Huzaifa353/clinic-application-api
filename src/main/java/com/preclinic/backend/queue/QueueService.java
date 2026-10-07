package com.preclinic.backend.queue;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.preclinic.backend.billing.InvoiceService;
import com.preclinic.backend.common.ApiException;
import com.preclinic.backend.common.AuditService;
import com.preclinic.backend.common.ClinicClock;
import com.preclinic.backend.common.NumberSequenceService;
import com.preclinic.backend.event.EventBus;
import com.preclinic.backend.notification.NotificationService;
import com.preclinic.backend.queue.QueueDtos.IntakeRequest;
import com.preclinic.backend.queue.QueueDtos.QueueBilling;
import com.preclinic.backend.queue.QueueDtos.QueueItemDto;
import com.preclinic.backend.queue.QueueDtos.QueueStats;
import com.preclinic.backend.queue.QueueDtos.VitalsDto;
import com.preclinic.backend.security.CurrentUser;
import com.preclinic.backend.user.AppUser;
import com.preclinic.backend.user.DoctorDirectory;

/**
 * The daily queue (a "visit" per patient attendance). The two busiest screens depend on this class:
 * the assistant's intake ("Add to Queue") and the doctor's call/start/complete flow. Every operation
 * here is one database transaction, so a half-finished intake (token issued but no invoice, say) can
 * never exist.
 */
@Service
public class QueueService {

	/** Statuses where the patient is still part of today's live queue. */
	private static final String ACTIVE = "('waiting','consulting','hold','skipped')";

	private static final Map<String, Set<String>> TRANSITIONS = Map.of(
			"waiting", Set.of("consulting", "hold", "skipped", "cancelled", "completed"),
			"consulting", Set.of("waiting", "hold", "completed", "cancelled"),
			"hold", Set.of("waiting", "consulting", "completed", "cancelled"),
			"skipped", Set.of("waiting", "consulting", "completed", "cancelled"),
			"completed", Set.of("waiting", "consulting"),
			"cancelled", Set.of());

	private static final String SELECT = """
			select v.id, v.token_no, v.queue_date, v.patient_id, p.patient_no, p.name as patient_name, p.mobile,
			       p.gender, coalesce(extract(year from age(:today, p.date_of_birth))::int, p.age_years) as age,
			       v.status, v.urgent, v.source, v.appointment_id, v.sort_order,
			       v.bp_systolic, v.bp_diastolic, v.temperature_f, v.pulse, v.weight_kg, v.spo2,
			       v.checked_in_at, v.consult_started_at, v.completed_at, v.doctor_id, d.full_name as doctor_name,
			       exists (select 1 from consultation c where c.visit_id = v.id) as has_consultation,
			       inv.id as invoice_id, inv.invoice_no, inv.consultation_fee, inv.additional_charges, inv.discount,
			       inv.total, inv.paid, inv.balance, inv.payment_status,
			       (select pm.method from payment pm where pm.invoice_id = inv.id
			         order by pm.paid_at desc, pm.id desc limit 1) as payment_method
			from visit v
			join patient p on p.id = v.patient_id
			left join app_user d on d.id = v.doctor_id
			left join lateral (select * from invoice_summary i where i.visit_id = v.id order by i.id limit 1) inv on true
			""";

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ClinicClock clock;
	private final NumberSequenceService sequences;
	private final InvoiceService invoices;
	private final DoctorDirectory doctors;
	private final NotificationService notifications;
	private final AuditService audit;
	private final EventBus events;

	public QueueService(JdbcClient jdbc, CurrentUser currentUser, ClinicClock clock, NumberSequenceService sequences,
			InvoiceService invoices, DoctorDirectory doctors,
			NotificationService notifications, AuditService audit, EventBus events) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.clock = clock;
		this.sequences = sequences;
		this.invoices = invoices;
		this.doctors = doctors;
		this.notifications = notifications;
		this.audit = audit;
		this.events = events;
	}

	// --- reads ---------------------------------------------------------------------

	@Transactional(readOnly = true)
	public QueueItemDto get(long id) {
		return jdbc.sql(SELECT + " where v.id = :id and v.clinic_id = :c")
				.param("id", id).param("c", currentUser.clinicId()).param("today", clock.today())
				.query(mapper()).optional()
				.orElseThrow(() -> ApiException.notFound("QUEUE_ITEM_NOT_FOUND", "No such queue entry."));
	}

	/** One day's queue in display order, optionally filtered. Defaults to today. */
	@Transactional(readOnly = true)
	public List<QueueItemDto> list(LocalDate date, String status, String source, String q) {
		Map<String, Object> params = new HashMap<>();
		params.put("c", currentUser.clinicId());
		params.put("today", clock.today());
		params.put("date", date != null ? date : clock.today());
		StringBuilder where = new StringBuilder(" where v.clinic_id = :c and v.queue_date = :date");
		if (status != null && !status.isBlank()) {
			where.append(" and v.status = :status");
			params.put("status", status);
		}
		if (source != null && !source.isBlank()) {
			where.append(" and v.source = :source");
			params.put("source", source);
		}
		if (q != null && !q.isBlank()) {
			where.append(" and (lower(p.name) like :text escape '\\'")
					.append(" or p.mobile_norm like :text escape '\\'")
					.append(" or lower('p' || lpad(p.patient_no::text, 5, '0')) like :text escape '\\'")
					.append(" or lpad(v.token_no::text, 2, '0') like :text escape '\\')");
			params.put("text", "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%")
					.replace("_", "\\_") + "%");
		}
		return jdbc.sql(SELECT + where + " order by v.sort_order, v.id").params(params).query(mapper()).list();
	}

	@Transactional(readOnly = true)
	public QueueStats stats(LocalDate date) {
		Map<String, Object> params = new HashMap<>();
		params.put("c", currentUser.clinicId());
		params.put("date", date != null ? date : clock.today());
		params.put("tz", clock.zone().getId());
		return jdbc.sql("""
				select count(*) as total,
				       count(*) filter (where status = 'waiting') as waiting,
				       count(*) filter (where status = 'consulting') as consulting,
				       count(*) filter (where status = 'hold') as hold,
				       count(*) filter (where status = 'completed') as completed,
				       count(*) filter (where status = 'skipped') as skipped,
				       count(*) filter (where status = 'cancelled') as cancelled,
				       coalesce((select sum(pm.amount) from payment pm join invoice i on i.id = pm.invoice_id
				                  where pm.clinic_id = :c and not i.voided
				                    and (pm.paid_at at time zone :tz)::date = :date), 0) as collected
				from visit where clinic_id = :c and queue_date = :date
				""").params(params).query(QueueStats.class).single();
	}

	// --- intake --------------------------------------------------------------------

	/**
	 * "Add to Queue", in one transaction: link the patient's same-day appointment (if any), issue the
	 * next token of the day, record the visit with its vitals, and create the invoice with whatever was
	 * paid. If any step fails nothing is kept - no orphan token, no invoice without a visit.
	 *
	 * @param force add the patient even if they are already waiting today (rare: a second, separate visit)
	 */
	@Transactional
	public QueueItemDto intake(IntakeRequest r, boolean force) {
		long clinicId = currentUser.clinicId();
		LocalDate today = clock.today();
		// Lock the patient for the rest of this transaction: two simultaneous check-ins of the same
		// patient (a double click, two assistants) are processed one after the other, so the
		// "already in the queue" check below cannot be fooled by a race.
		Integer exists = jdbc.sql("select 1 from patient where id = :p and clinic_id = :c for update")
				.param("p", r.patientId()).param("c", clinicId).query(Integer.class).optional().orElse(null);
		if (exists == null) {
			throw ApiException.notFound("PATIENT_NOT_FOUND", "No such patient.");
		}

		if (!force) {
			Integer active = jdbc.sql("select 1 from visit where clinic_id = :c and queue_date = :d and patient_id = :p"
					+ " and status in " + ACTIVE + " limit 1")
					.param("c", clinicId).param("d", today).param("p", r.patientId())
					.query(Integer.class).optional().orElse(null);
			if (active != null) {
				throw ApiException.conflict("PATIENT_ALREADY_IN_QUEUE", "This patient is already in today's queue.");
			}
		}

		Long appointmentId = resolveAppointment(r.patientId(), r.appointmentId(), today);
		long token = sequences.nextToken(clinicId, today);
		VitalsDto v = r.vitals() == null ? new VitalsDto(null, null, null, null, null, null) : r.vitals();
		long visitId = jdbc.sql("""
				insert into visit (clinic_id, queue_date, token_no, patient_id, appointment_id, source, urgent, sort_order,
				                   bp_systolic, bp_diastolic, temperature_f, pulse, weight_kg, spo2, created_by)
				values (:c, :date, :token, :p, :appt, :source, :urgent,
				        coalesce((select max(sort_order) from visit where clinic_id = :c and queue_date = :date), 0) + 1,
				        :bps, :bpd, :temp, :pulse, :weight, :spo2, :user)
				returning id
				""")
				.param("c", clinicId).param("date", today).param("token", (int) token).param("p", r.patientId())
				.param("appt", appointmentId).param("source", appointmentId != null ? "appointment" : "walk-in")
				.param("urgent", Boolean.TRUE.equals(r.urgent()))
				.param("bps", v.bpSystolic()).param("bpd", v.bpDiastolic()).param("temp", v.temperature())
				.param("pulse", v.pulse()).param("weight", v.weight()).param("spo2", v.spo2())
				.param("user", currentUser.id()).query(Long.class).single();

		if (appointmentId != null) {
			jdbc.sql("update appointment set status = 'checked-in', updated_at = now() where id = :id")
					.param("id", appointmentId).update();
			events.publish(clinicId, EventBus.Type.APPOINTMENTS);
		}

		invoices.create(new InvoiceService.NewInvoice(r.patientId(), visitId, null, r.serviceId(), r.description(),
				r.consultationFee(), r.additionalCharges(), r.discount(), r.amountPaid(), r.paymentMethod(),
				r.reference()));

		audit.log("visit.intake", "visit", visitId, AuditService.detail("token", token, "patientId", r.patientId(),
				"appointmentId", appointmentId, "urgent", Boolean.TRUE.equals(r.urgent())));
		events.publish(clinicId, EventBus.Type.QUEUE);
		return get(visitId);
	}

	/**
	 * An explicit appointment must belong to this patient, be today's, and still be open. With none
	 * given, today's earliest open appointment of the patient is linked automatically (they were
	 * simply searched for instead of being checked in from the appointment list).
	 */
	private Long resolveAppointment(long patientId, Long requestedId, LocalDate today) {
		long clinicId = currentUser.clinicId();
		if (requestedId == null) {
			return jdbc.sql("""
					select id from appointment
					where clinic_id = :c and patient_id = :p and appt_date = :d and status in ('scheduled','arrived')
					order by appt_time, id limit 1 for update
					""").param("c", clinicId).param("p", patientId).param("d", today)
					.query(Long.class).optional().orElse(null);
		}
		var appt = jdbc.sql("select patient_id, appt_date, status from appointment where id = :id and clinic_id = :c for update")
				.param("id", requestedId).param("c", clinicId)
				.query((rs, i) -> new Object[] { rs.getLong("patient_id"), rs.getObject("appt_date", LocalDate.class),
						rs.getString("status") })
				.optional().orElseThrow(() -> ApiException.notFound("APPOINTMENT_NOT_FOUND", "No such appointment."));
		if ((Long) appt[0] != patientId) {
			throw ApiException.unprocessable("APPOINTMENT_PATIENT_MISMATCH", "That appointment belongs to another patient.");
		}
		if (!today.equals(appt[1])) {
			throw ApiException.unprocessable("APPOINTMENT_NOT_TODAY", "Only today's appointments can be checked in.");
		}
		if (!"scheduled".equals(appt[2]) && !"arrived".equals(appt[2])) {
			throw ApiException.unprocessable("APPOINTMENT_NOT_AVAILABLE",
					"That appointment is already " + appt[2].toString().replace('-', ' ') + ".");
		}
		return requestedId;
	}

	// --- status changes ------------------------------------------------------------

	private record Locked(String status, long patientId, Long appointmentId, LocalDate queueDate, int token) {
	}

	/**
	 * Moves a patient through the queue. Valid moves are listed in {@link #TRANSITIONS}. Starting a
	 * consultation records the doctor, refuses if that doctor is already with someone, and notifies
	 * the assistant. Cancelling a checked-in appointment's visit puts the appointment back to "arrived".
	 */
	@Transactional
	public QueueItemDto changeStatus(long id, String target) {
		long clinicId = currentUser.clinicId();
		Locked visit = jdbc.sql("""
				select status, patient_id, appointment_id, queue_date, token_no from visit
				where id = :id and clinic_id = :c for update
				""").param("id", id).param("c", clinicId)
				.query((rs, i) -> new Locked(rs.getString("status"), rs.getLong("patient_id"),
						nullableLong(rs, "appointment_id"), rs.getObject("queue_date", LocalDate.class),
						rs.getInt("token_no")))
				.optional().orElseThrow(() -> ApiException.notFound("QUEUE_ITEM_NOT_FOUND", "No such queue entry."));

		if (visit.status().equals(target)) {
			return get(id);
		}
		if (!TRANSITIONS.get(visit.status()).contains(target)) {
			throw ApiException.unprocessable("INVALID_TRANSITION",
					"A patient who is " + visit.status() + " cannot be set to " + target + ".");
		}
		boolean hasConsultation = Boolean.TRUE.equals(jdbc.sql("select exists (select 1 from consultation where visit_id = :id)")
				.param("id", id).query(Boolean.class).single());
		if (hasConsultation && !target.equals("completed") && !target.equals("consulting")) {
			throw ApiException.unprocessable("VISIT_HAS_CONSULTATION",
					"A consultation has already been recorded for this visit.");
		}
		if (hasConsultation && visit.status().equals("completed") && target.equals("waiting")) {
			throw ApiException.unprocessable("VISIT_HAS_CONSULTATION",
					"A consultation has already been recorded for this visit.");
		}
		boolean startsWork = target.equals("consulting") || target.equals("waiting");
		if (startsWork && !visit.queueDate().equals(clock.today())) {
			throw ApiException.unprocessable("QUEUE_DAY_CLOSED", "That queue day has ended; the patient cannot be called now.");
		}

		Long doctorId = null;
		if (target.equals("consulting")) {
			AppUser doctor = doctors.resolve(currentUser.isDoctor() ? currentUser.id() : null);
			doctorId = doctor.getId();
			Integer busy = jdbc.sql("select 1 from visit where doctor_id = :d and status = 'consulting' and id <> :id limit 1")
					.param("d", doctorId).param("id", id).query(Integer.class).optional().orElse(null);
			if (busy != null) {
				throw ApiException.conflict("QUEUE_DOCTOR_BUSY", "The doctor is already consulting another patient.");
			}
		}

		jdbc.sql("""
				update visit set status = :status,
				       doctor_id = case when :target = 'consulting' then :doctor else doctor_id end,
				       consult_started_at = case when :target = 'consulting' then coalesce(consult_started_at, now())
				                                 else consult_started_at end,
				       completed_at = case when :target = 'completed' then now()
				                           when :target in ('waiting','consulting') then null else completed_at end,
				       updated_at = now()
				where id = :id
				""").param("status", target).param("target", target).param("doctor", doctorId).param("id", id).update();

		if (target.equals("cancelled") && visit.appointmentId() != null) {
			jdbc.sql("update appointment set status = 'arrived', updated_at = now() where id = :a and status = 'checked-in'")
					.param("a", visit.appointmentId()).update();
			events.publish(clinicId, EventBus.Type.APPOINTMENTS);
		}
		if (target.equals("consulting")) {
			String patientName = jdbc.sql("select name from patient where id = :p").param("p", visit.patientId())
					.query(String.class).single();
			String doctorName = jdbc.sql("select full_name from app_user where id = :d").param("d", doctorId)
					.query(String.class).single();
			notifications.consultationStarted(id, doctorName, patientName, visit.token());
			events.publish(clinicId, EventBus.Type.DOCTOR_STATUS);
		}
		audit.log("visit.status", "visit", id, AuditService.detail("from", visit.status(), "to", target,
				"token", visit.token()));
		events.publish(clinicId, EventBus.Type.QUEUE);
		return get(id);
	}

	/**
	 * Calls the first waiting patient (by the order the assistant arranged). Concurrent callers cannot
	 * pick the same patient: the row is locked and skipped by anyone else.
	 */
	@Transactional
	public QueueItemDto callNext() {
		long clinicId = currentUser.clinicId();
		AppUser doctor = doctors.resolve(currentUser.isDoctor() ? currentUser.id() : null);
		Integer busy = jdbc.sql("select 1 from visit where doctor_id = :d and status = 'consulting' limit 1")
				.param("d", doctor.getId()).query(Integer.class).optional().orElse(null);
		if (busy != null) {
			throw ApiException.conflict("QUEUE_DOCTOR_BUSY", "The doctor is already consulting another patient.");
		}
		Long next = jdbc.sql("""
				select id from visit where clinic_id = :c and queue_date = :d and status = 'waiting'
				order by sort_order, id limit 1 for update skip locked
				""").param("c", clinicId).param("d", clock.today()).query(Long.class).optional().orElse(null);
		if (next == null) {
			// Nothing free to take. If patients ARE waiting, other callers hold them right now (a concurrent
			// call) - that is "busy", not "empty".
			Integer waiting = jdbc.sql("select 1 from visit where clinic_id = :c and queue_date = :d and status = 'waiting' limit 1")
					.param("c", clinicId).param("d", clock.today()).query(Integer.class).optional().orElse(null);
			if (waiting != null) {
				throw ApiException.conflict("QUEUE_DOCTOR_BUSY", "The doctor is already consulting another patient.");
			}
			throw ApiException.notFound("QUEUE_EMPTY", "No patients are waiting.");
		}
		return changeStatus(next, "consulting");
	}

	/** Removing from the queue keeps the record (it appears in history) but cancels it. */
	@Transactional
	public void remove(long id) {
		changeStatus(id, "cancelled");
	}

	// --- edits ---------------------------------------------------------------------

	@Transactional
	public QueueItemDto updateVitals(long id, VitalsDto v) {
		int changed = jdbc.sql("""
				update visit set bp_systolic = :bps, bp_diastolic = :bpd, temperature_f = :temp, pulse = :pulse,
				       weight_kg = :weight, spo2 = :spo2, updated_at = now()
				where id = :id and clinic_id = :c
				""")
				.param("bps", v.bpSystolic()).param("bpd", v.bpDiastolic()).param("temp", v.temperature())
				.param("pulse", v.pulse()).param("weight", v.weight()).param("spo2", v.spo2())
				.param("id", id).param("c", currentUser.clinicId()).update();
		if (changed == 0) {
			throw ApiException.notFound("QUEUE_ITEM_NOT_FOUND", "No such queue entry.");
		}
		events.publish(currentUser.clinicId(), EventBus.Type.QUEUE);
		return get(id);
	}

	@Transactional
	public QueueItemDto setUrgent(long id, boolean urgent) {
		int changed = jdbc.sql("update visit set urgent = :u, updated_at = now() where id = :id and clinic_id = :c")
				.param("u", urgent).param("id", id).param("c", currentUser.clinicId()).update();
		if (changed == 0) {
			throw ApiException.notFound("QUEUE_ITEM_NOT_FOUND", "No such queue entry.");
		}
		events.publish(currentUser.clinicId(), EventBus.Type.QUEUE);
		return get(id);
	}

	/**
	 * Drag-and-drop ordering of today's queue. The supplied ids go first in the given order; any of
	 * today's entries left out keep their relative order after them.
	 */
	@Transactional
	public List<QueueItemDto> reorder(List<Long> orderedIds) {
		long clinicId = currentUser.clinicId();
		LocalDate today = clock.today();
		List<Long> current = jdbc.sql("""
				select id from visit where clinic_id = :c and queue_date = :d order by sort_order, id for update
				""").param("c", clinicId).param("d", today).query(Long.class).list();
		Set<Long> known = new LinkedHashSet<>(current);
		Set<Long> seen = new LinkedHashSet<>();
		for (Long id : orderedIds) {
			if (!known.contains(id) || !seen.add(id)) {
				throw ApiException.unprocessable("INVALID_ORDER",
						"The order must list each of today's queue entries at most once.");
			}
		}
		List<Long> finalOrder = new ArrayList<>(seen);
		current.stream().filter(id -> !seen.contains(id)).forEach(finalOrder::add);
		for (int i = 0; i < finalOrder.size(); i++) {
			jdbc.sql("update visit set sort_order = :o, updated_at = now() where id = :id")
					.param("o", i + 1).param("id", finalOrder.get(i)).update();
		}
		events.publish(clinicId, EventBus.Type.QUEUE);
		return list(today, null, null, null);
	}

	// --- mapping -------------------------------------------------------------------

	private static Long nullableLong(ResultSet rs, String column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}

	private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}

	private RowMapper<QueueItemDto> mapper() {
		return (rs, i) -> {
			long patientNo = rs.getLong("patient_no");
			OffsetDateTime started = rs.getObject("consult_started_at", OffsetDateTime.class);
			OffsetDateTime completed = rs.getObject("completed_at", OffsetDateTime.class);
			Long invoiceId = nullableLong(rs, "invoice_id");
			QueueBilling billing = null;
			if (invoiceId != null) {
				BigDecimal paid = rs.getBigDecimal("paid");
				billing = new QueueBilling(invoiceId, "INV-" + String.format("%05d", rs.getLong("invoice_no")),
						rs.getBigDecimal("consultation_fee"), rs.getBigDecimal("additional_charges"),
						rs.getBigDecimal("discount"), rs.getBigDecimal("total"), paid, rs.getBigDecimal("balance"),
						rs.getString("payment_status"), rs.getString("payment_method"));
			}
			VitalsDto vitals = new VitalsDto(nullableInt(rs, "bp_systolic"), nullableInt(rs, "bp_diastolic"),
					rs.getBigDecimal("temperature_f"), nullableInt(rs, "pulse"), rs.getBigDecimal("weight_kg"),
					nullableInt(rs, "spo2"));
			return new QueueItemDto(rs.getLong("id"), String.format("%02d", rs.getInt("token_no")),
					rs.getObject("queue_date", LocalDate.class), rs.getLong("patient_id"),
					"P" + String.format("%05d", patientNo), rs.getString("patient_name"), rs.getString("mobile"),
					rs.getString("gender"), nullableInt(rs, "age"), rs.getString("status"), rs.getBoolean("urgent"),
					rs.getString("source"), nullableLong(rs, "appointment_id"), rs.getInt("sort_order"), vitals,
					rs.getObject("checked_in_at", OffsetDateTime.class).toInstant(),
					started == null ? null : started.toInstant(), completed == null ? null : completed.toInstant(),
					nullableLong(rs, "doctor_id"), rs.getString("doctor_name"), rs.getBoolean("has_consultation"),
					billing);
		};
	}
}
