package com.preclinic.backend.followup;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.preclinic.backend.appointment.AppointmentDtos.AppointmentDto;
import com.preclinic.backend.appointment.AppointmentDtos.CreateAppointmentRequest;
import com.preclinic.backend.appointment.AppointmentService;
import com.preclinic.backend.common.ApiException;
import com.preclinic.backend.common.AuditService;
import com.preclinic.backend.common.ClinicClock;
import com.preclinic.backend.common.PageResult;
import com.preclinic.backend.common.PgArrays;
import com.preclinic.backend.consultation.ConsultationService;
import com.preclinic.backend.event.EventBus;
import com.preclinic.backend.followup.FollowUpDtos.FollowUpItemDto;
import com.preclinic.backend.followup.FollowUpDtos.FollowUpStats;
import com.preclinic.backend.followup.FollowUpDtos.ScheduleRequest;
import com.preclinic.backend.patient.MobileNumbers;
import com.preclinic.backend.security.CurrentUser;

/**
 * The follow-up work list: who is due back, who is overdue, who has been contacted, and the actions
 * the assistant takes (remind, record contact outcome, book the visit, reschedule, close). The plan
 * itself is created by the doctor's consultation; here it is only ever worked or closed.
 */
@Service
public class FollowUpService {

	private static final String EFFECTIVE = "coalesce(f.override_due_date, f.due_date)";

	private static final String SELECT = """
			select f.id, f.consultation_id, c.consult_no, c.visit_id, v.queue_date, f.patient_id, p.patient_no,
			       p.name as patient_name, p.mobile, c.doctor_id, d.full_name as doctor_name,
			       array(select cd.name from consultation_diagnosis cd where cd.consultation_id = c.id
			             order by cd.position) as diagnoses,
			       f.days, f.reason, f.due_date, f.override_due_date, f.status, f.contact_status, f.last_reminded_on,
			       f.linked_appointment_id, a.appt_date, a.appt_time, a.status as appt_status, f.cancellation_reason,
			       f.resolved_at
			""";

	private static final String FROM = """
			from follow_up f
			join consultation c on c.id = f.consultation_id
			join visit v on v.id = c.visit_id
			join patient p on p.id = f.patient_id
			join app_user d on d.id = c.doctor_id
			left join appointment a on a.id = f.linked_appointment_id
			""";

	public record Query(String tab, String q, Long doctorId, String contact, LocalDate from, LocalDate to, String sort,
			String dir, int skip, int limit) {
	}

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ClinicClock clock;
	private final AppointmentService appointments;
	private final AuditService audit;
	private final EventBus events;

	public FollowUpService(JdbcClient jdbc, CurrentUser currentUser, ClinicClock clock,
			AppointmentService appointments, AuditService audit, EventBus events) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.clock = clock;
		this.appointments = appointments;
		this.audit = audit;
		this.events = events;
	}

	// ===== reads =====================================================================

	@Transactional(readOnly = true)
	public FollowUpItemDto get(long id) {
		return jdbc.sql(SELECT + FROM + " where f.id = :id and f.clinic_id = :c")
				.param("id", id).param("c", currentUser.clinicId()).query(mapper()).optional()
				.orElseThrow(() -> ApiException.notFound("FOLLOW_UP_NOT_FOUND", "No such follow-up."));
	}

	@Transactional(readOnly = true)
	public PageResult<FollowUpItemDto> list(Query q) {
		Map<String, Object> params = new HashMap<>();
		params.put("c", currentUser.clinicId());
		params.put("today", clock.today());
		StringBuilder where = new StringBuilder(" where f.clinic_id = :c");
		String tab = q.tab() == null ? "all" : q.tab();
		switch (tab) {
			case "due" -> where.append(" and f.status is null and ").append(EFFECTIVE).append(" = :today");
			case "overdue" -> where.append(" and f.status is null and ").append(EFFECTIVE).append(" < :today");
			case "upcoming" -> where.append(" and f.status is null and ").append(EFFECTIVE).append(" > :today");
			case "completed" -> where.append(" and f.status is not null");
			case "all" -> {
			}
			default -> throw ApiException.badRequest("INVALID_TAB", "Unknown tab: " + tab);
		}
		if (q.doctorId() != null) {
			where.append(" and c.doctor_id = :doctorId");
			params.put("doctorId", q.doctorId());
		}
		if (q.contact() != null && !q.contact().isBlank()) {
			if (q.contact().equals("none")) {
				where.append(" and f.contact_status is null");
			}
			else {
				where.append(" and f.contact_status = :contact");
				params.put("contact", q.contact());
			}
		}
		if (q.from() != null) {
			where.append(" and ").append(EFFECTIVE).append(" >= :from");
			params.put("from", q.from());
		}
		if (q.to() != null) {
			where.append(" and ").append(EFFECTIVE).append(" <= :to");
			params.put("to", q.to());
		}
		if (q.q() != null && !q.q().isBlank()) {
			String text = q.q().trim().toLowerCase();
			where.append(" and (lower(p.name) like :text escape '\\'")
					.append(" or lower('p' || lpad(p.patient_no::text, 5, '0')) like :text escape '\\'")
					.append(" or exists (select 1 from consultation_diagnosis cd where cd.consultation_id = c.id")
					.append("      and lower(cd.name) like :text escape '\\')");
			params.put("text", like(text));
			if (text.matches("[0-9+\\-\\s()]+")) {
				where.append(" or p.mobile_norm like :mobile escape '\\'");
				params.put("mobile", like(MobileNumbers.normalize(text)));
			}
			where.append(")");
		}
		long total = jdbc.sql("select count(*) " + FROM + where).params(params).query(Long.class).single();
		params.put("limit", q.limit());
		params.put("skip", q.skip());
		List<FollowUpItemDto> page = jdbc.sql(SELECT + FROM + where + " order by " + orderBy(q)
				+ " limit :limit offset :skip").params(params).query(mapper()).list();
		return new PageResult<>(page, total);
	}

	private static String orderBy(Query q) {
		String dir = "desc".equalsIgnoreCase(q.dir()) ? " desc" : " asc";
		String key = q.sort() == null ? "dueDate" : q.sort();
		return switch (key) {
			case "dueDate" -> EFFECTIVE + dir + ", f.id";
			case "visitDate" -> "v.queue_date" + dir + ", f.id";
			case "patient" -> "lower(p.name)" + dir + ", f.id";
			case "doctor" -> "lower(d.full_name)" + dir + ", f.id";
			default -> throw ApiException.badRequest("INVALID_SORT", "Unknown sort: " + key);
		};
	}

	/** The assistant dashboard's widget: open follow-ups due today or already overdue, oldest first. */
	@Transactional(readOnly = true)
	public List<FollowUpItemDto> dueOrOverdue() {
		return jdbc.sql(SELECT + FROM + " where f.clinic_id = :c and f.status is null and " + EFFECTIVE
				+ " <= :today order by " + EFFECTIVE + ", f.id limit 200")
				.param("c", currentUser.clinicId()).param("today", clock.today()).query(mapper()).list();
	}

	@Transactional(readOnly = true)
	public FollowUpStats stats() {
		return jdbc.sql("select count(*) filter (where f.status is null and " + EFFECTIVE + " = :today) as due_today,"
				+ " count(*) filter (where f.status is null and " + EFFECTIVE + " < :today) as overdue,"
				+ " count(*) filter (where f.status is null and " + EFFECTIVE + " > :today) as upcoming,"
				+ " count(*) filter (where f.status is not null) as completed"
				+ " from follow_up f where f.clinic_id = :c")
				.param("c", currentUser.clinicId()).param("today", clock.today()).query(FollowUpStats.class).single();
	}

	// ===== actions ===================================================================

	/** "Reminder sent" (WhatsApp/SMS/call made): stamps today so the dashboard stops nagging. */
	@Transactional
	public FollowUpItemDto remind(long id) {
		requireOpen(id);
		jdbc.sql("update follow_up set last_reminded_on = :today where id = :id")
				.param("today", clock.today()).param("id", id).update();
		return changed(id, "followup.remind", null);
	}

	/** Records the outcome of contacting the patient; this also counts as a reminder today. */
	@Transactional
	public FollowUpItemDto setContactStatus(long id, String status) {
		requireOpen(id);
		jdbc.sql("update follow_up set contact_status = :s, last_reminded_on = :today where id = :id")
				.param("s", status).param("today", clock.today()).param("id", id).update();
		return changed(id, "followup.contact", status);
	}

	/** Moves the due date; the original is kept so the history stays intact. A past date would be overdue at once, so it is refused. */
	@Transactional
	public FollowUpItemDto reschedule(long id, LocalDate date) {
		requireOpen(id);
		if (date.isBefore(clock.today())) {
			throw ApiException.unprocessable("FOLLOW_UP_DATE_IN_PAST", "A follow-up cannot be moved into the past.");
		}
		jdbc.sql("update follow_up set override_due_date = :d where id = :id").param("d", date).param("id", id).update();
		return changed(id, "followup.reschedule", date.toString());
	}

	@Transactional
	public FollowUpItemDto complete(long id) {
		requireOpen(id);
		jdbc.sql("update follow_up set status = 'completed', resolved_at = now() where id = :id").param("id", id).update();
		return changed(id, "followup.complete", null);
	}

	@Transactional
	public FollowUpItemDto cancel(long id, String reason) {
		requireOpen(id);
		jdbc.sql("update follow_up set status = 'cancelled', cancellation_reason = :r, resolved_at = now() where id = :id")
				.param("r", reason == null || reason.isBlank() ? null : reason.trim()).param("id", id).update();
		return changed(id, "followup.cancel", reason);
	}

	/**
	 * The follow-up becomes a real appointment (type "Follow-up") for the same patient, linked back to
	 * it. The appointment rules apply (not in the past, doctor slot free); {@code force} allows a day on
	 * which the patient already has another appointment.
	 */
	@Transactional
	public FollowUpItemDto schedule(long id, ScheduleRequest r, boolean force) {
		FollowUpItemDto followUp = requireOpen(id);
		AppointmentDto appointment = appointments.create(new CreateAppointmentRequest(followUp.patientId(), r.doctorId(),
				r.date(), r.time(), "Follow-up", r.notes()), force);
		jdbc.sql("update follow_up set linked_appointment_id = :a where id = :id")
				.param("a", appointment.id()).param("id", id).update();
		return changed(id, "followup.schedule", "appointment " + appointment.displayId());
	}

	private FollowUpItemDto requireOpen(long id) {
		FollowUpItemDto followUp = jdbc.sql(SELECT + FROM + " where f.id = :id and f.clinic_id = :c for update of f")
				.param("id", id).param("c", currentUser.clinicId()).query(mapper()).optional()
				.orElseThrow(() -> ApiException.notFound("FOLLOW_UP_NOT_FOUND", "No such follow-up."));
		if (followUp.status() != null) {
			throw ApiException.unprocessable("FOLLOW_UP_CLOSED", "This follow-up is already " + followUp.status() + ".");
		}
		return followUp;
	}

	private FollowUpItemDto changed(long id, String action, String detail) {
		audit.log(action, "follow_up", id, AuditService.detail("detail", detail));
		events.publish(currentUser.clinicId(), EventBus.Type.FOLLOW_UP);
		return get(id);
	}

	// ===== mapping ===================================================================

	private static String like(String value) {
		return "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
	}

	private RowMapper<FollowUpItemDto> mapper() {
		LocalDate today = clock.today();
		return (rs, i) -> {
			LocalDate original = rs.getObject("due_date", LocalDate.class);
			LocalDate override = rs.getObject("override_due_date", LocalDate.class);
			LocalDate effective = override != null ? override : original;
			String status = rs.getString("status");
			LocalDate reminded = rs.getObject("last_reminded_on", LocalDate.class);
			long appt = rs.getLong("linked_appointment_id");
			boolean hasAppointment = !rs.wasNull();
			LocalTime apptTime = rs.getObject("appt_time", LocalTime.class);
			OffsetDateTime resolved = rs.getObject("resolved_at", OffsetDateTime.class);
			long consultNo = rs.getLong("consult_no");
			long visit = rs.getLong("visit_id");
			boolean hasVisit = !rs.wasNull();
			long overdue = status == null && effective.isBefore(today) ? ChronoUnit.DAYS.between(effective, today) : 0;
			return new FollowUpItemDto(rs.getLong("id"), rs.getLong("consultation_id"),
					"C" + String.format("%05d", consultNo), hasVisit ? visit : null,
					rs.getObject("queue_date", LocalDate.class), rs.getLong("patient_id"),
					"P" + String.format("%05d", rs.getLong("patient_no")), rs.getString("patient_name"),
					rs.getString("mobile"), rs.getLong("doctor_id"), rs.getString("doctor_name"),
					PgArrays.read(rs.getArray("diagnoses")), rs.getInt("days"), rs.getString("reason"), effective,
					original, ConsultationService.followUpState(status, effective, today), overdue, status,
					rs.getString("contact_status"), reminded, today.equals(reminded), hasAppointment ? appt : null,
					rs.getObject("appt_date", LocalDate.class), apptTime == null ? null : apptTime.toString(),
					rs.getString("appt_status"), rs.getString("cancellation_reason"),
					resolved == null ? null : resolved.toInstant());
		};
	}
}
