package com.preclinic.backend.appointment;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.preclinic.backend.appointment.AppointmentDtos.AppointmentDto;
import com.preclinic.backend.appointment.AppointmentDtos.AppointmentStats;
import com.preclinic.backend.appointment.AppointmentDtos.ConflictsDto;
import com.preclinic.backend.appointment.AppointmentDtos.CreateAppointmentRequest;
import com.preclinic.backend.appointment.AppointmentDtos.RescheduleRequest;
import com.preclinic.backend.common.ApiException;
import com.preclinic.backend.common.ClinicClock;
import com.preclinic.backend.common.NumberSequenceService;
import com.preclinic.backend.common.PageResult;
import com.preclinic.backend.common.SequenceKind;
import com.preclinic.backend.event.EventBus;
import com.preclinic.backend.patient.PatientService;
import com.preclinic.backend.security.CurrentUser;
import com.preclinic.backend.user.DoctorDirectory;

@Service
public class AppointmentService {

	/** Statuses that still occupy a doctor's slot (mirrors the partial unique index in V4). */
	private static final String OPEN = "('scheduled','arrived','checked-in')";

	private static final String SELECT = """
			select a.id, a.appointment_no, a.patient_id, p.name as patient_name, p.patient_no, p.mobile,
			       a.doctor_id, u.full_name as doctor_name, a.appt_date, a.appt_time, a.type, a.status, a.notes,
			       a.cancellation_reason, a.rescheduled_from_id,
			       (select r.id from appointment r where r.rescheduled_from_id = a.id order by r.id limit 1) as rescheduled_to_id,
			       a.reminder_status, v.id as visit_id, v.token_no, v.status as visit_status
			from appointment a
			join patient p on p.id = a.patient_id
			join app_user u on u.id = a.doctor_id
			left join lateral (select id, token_no, status from visit where appointment_id = a.id
			                   order by id desc limit 1) v on true
			""";

	public record Query(LocalDate date, LocalDate from, LocalDate to, List<String> statuses, String type, Long doctorId,
			Long patientId, String q, String dir, int skip, int limit) {
	}

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ClinicClock clock;
	private final NumberSequenceService sequences;
	private final PatientService patients;
	private final DoctorDirectory doctors;
	private final EventBus events;

	public AppointmentService(JdbcClient jdbc, CurrentUser currentUser, ClinicClock clock,
			NumberSequenceService sequences, PatientService patients, DoctorDirectory doctors, EventBus events) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.clock = clock;
		this.sequences = sequences;
		this.patients = patients;
		this.doctors = doctors;
		this.events = events;
	}

	// --- reads ---------------------------------------------------------------------

	@Transactional(readOnly = true)
	public AppointmentDto get(long id) {
		return jdbc.sql(SELECT + " where a.id = :id and a.clinic_id = :c")
				.param("id", id).param("c", currentUser.clinicId()).query(mapper()).optional()
				.orElseThrow(() -> ApiException.notFound("APPOINTMENT_NOT_FOUND", "No such appointment."));
	}

	@Transactional(readOnly = true)
	public PageResult<AppointmentDto> list(Query q) {
		Map<String, Object> params = new HashMap<>();
		params.put("c", currentUser.clinicId());
		StringBuilder where = new StringBuilder(" where a.clinic_id = :c");
		if (q.date() != null) {
			where.append(" and a.appt_date = :date");
			params.put("date", q.date());
		}
		if (q.from() != null) {
			where.append(" and a.appt_date >= :from");
			params.put("from", q.from());
		}
		if (q.to() != null) {
			where.append(" and a.appt_date <= :to");
			params.put("to", q.to());
		}
		if (q.statuses() != null && !q.statuses().isEmpty()) {
			where.append(" and a.status in (:statuses)");
			params.put("statuses", q.statuses());
		}
		if (q.type() != null && !q.type().isBlank()) {
			where.append(" and a.type = :type");
			params.put("type", q.type());
		}
		if (q.doctorId() != null) {
			where.append(" and a.doctor_id = :doctorId");
			params.put("doctorId", q.doctorId());
		}
		if (q.patientId() != null) {
			where.append(" and a.patient_id = :patientId");
			params.put("patientId", q.patientId());
		}
		if (q.q() != null && !q.q().isBlank()) {
			where.append(" and (lower(p.name) like :text escape '\\'")
					.append(" or p.mobile_norm like :text escape '\\'")
					.append(" or lower('p' || lpad(p.patient_no::text, 5, '0')) like :text escape '\\'")
					.append(" or lower('a' || lpad(a.appointment_no::text, 5, '0')) like :text escape '\\')");
			params.put("text", "%" + q.q().trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%")
					.replace("_", "\\_") + "%");
		}
		String dir = "desc".equalsIgnoreCase(q.dir()) ? " desc" : " asc";
		long total = jdbc.sql("select count(*) from appointment a join patient p on p.id = a.patient_id" + where)
				.params(params).query(Long.class).single();
		params.put("limit", q.limit());
		params.put("skip", q.skip());
		List<AppointmentDto> page = jdbc.sql(SELECT + where + " order by a.appt_date" + dir + ", a.appt_time" + dir
				+ ", a.id" + dir + " limit :limit offset :skip").params(params).query(mapper()).list();
		return new PageResult<>(page, total);
	}

	/** Day summary cards. "Completed" means the patient was seen (the linked visit finished). */
	@Transactional(readOnly = true)
	public AppointmentStats stats(LocalDate date) {
		return jdbc.sql("""
				select count(*) as total,
				       count(*) filter (where a.status in ('scheduled','arrived')) as scheduled,
				       count(*) filter (where v.status = 'completed') as completed,
				       count(*) filter (where a.status = 'cancelled') as cancelled,
				       count(*) filter (where a.status = 'no-show') as no_show
				from appointment a
				left join lateral (select status from visit where appointment_id = a.id order by id desc limit 1) v on true
				where a.clinic_id = :c and a.appt_date = :date
				""").param("c", currentUser.clinicId()).param("date", date).query(AppointmentStats.class).single();
	}

	/** What would collide if this booking were made. */
	@Transactional(readOnly = true)
	public ConflictsDto conflicts(long patientId, Long doctorId, LocalDate date, LocalTime time) {
		AppointmentDto sameDay = jdbc.sql(SELECT + " where a.clinic_id = :c and a.patient_id = :p and a.appt_date = :d"
				+ " and a.status in " + OPEN + " order by a.appt_time limit 1")
				.param("c", currentUser.clinicId()).param("p", patientId).param("d", date)
				.query(mapper()).optional().orElse(null);
		AppointmentDto slot = null;
		if (time != null) {
			long doctor = doctors.resolve(doctorId).getId();
			slot = jdbc.sql(SELECT + " where a.clinic_id = :c and a.doctor_id = :doc and a.appt_date = :d"
					+ " and a.appt_time = :t and a.status in " + OPEN + " limit 1")
					.param("c", currentUser.clinicId()).param("doc", doctor).param("d", date).param("t", time)
					.query(mapper()).optional().orElse(null);
		}
		return new ConflictsDto(sameDay, slot);
	}

	// --- writes --------------------------------------------------------------------

	/**
	 * Books an appointment. A taken doctor slot is always refused; the same patient already holding an
	 * open appointment that day is refused unless {@code force} (the screen asks the user first).
	 */
	@Transactional
	public AppointmentDto create(CreateAppointmentRequest r, boolean force) {
		if (r.date().isBefore(clock.today())) {
			throw ApiException.unprocessable("APPOINTMENT_IN_PAST", "An appointment cannot be booked in the past.");
		}
		patients.requireExists(r.patientId());
		long doctorId = doctors.resolve(r.doctorId()).getId();
		if (!force) {
			Integer clash = jdbc.sql("select 1 from appointment where clinic_id = :c and patient_id = :p"
					+ " and appt_date = :d and status in " + OPEN + " limit 1")
					.param("c", currentUser.clinicId()).param("p", r.patientId()).param("d", r.date())
					.query(Integer.class).optional().orElse(null);
			if (clash != null) {
				throw ApiException.conflict("PATIENT_ALREADY_BOOKED",
						"This patient already has an appointment on that day.");
			}
		}
		ensureSlotFree(doctorId, r.date(), r.time(), null);
		return insert(r.patientId(), doctorId, r.date(), r.time(), r.type(), r.notes(), null);
	}

	/**
	 * Refuses a slot another open appointment already holds. The partial unique index is the backstop for
	 * two people booking at the same instant; this check gives the normal, friendly answer first.
	 */
	private void ensureSlotFree(long doctorId, LocalDate date, LocalTime time, Long excludingId) {
		Integer taken = jdbc.sql("select 1 from appointment where clinic_id = :c and doctor_id = :d and appt_date = :date"
				+ " and appt_time = :t and status in " + OPEN + " and (cast(:exclude as bigint) is null or id <> :exclude) limit 1")
				.param("c", currentUser.clinicId()).param("d", doctorId).param("date", date).param("t", time)
				.param("exclude", excludingId).query(Integer.class).optional().orElse(null);
		if (taken != null) {
			throw ApiException.conflict("APPOINTMENT_SLOT_TAKEN",
					"The doctor already has an appointment at that date and time.");
		}
	}

	private AppointmentDto insert(long patientId, long doctorId, LocalDate date, LocalTime time, String type,
			String notes, Long rescheduledFrom) {
		long clinicId = currentUser.clinicId();
		long no = sequences.next(clinicId, SequenceKind.APPOINTMENT);
		long id = jdbc.sql("""
				insert into appointment (clinic_id, appointment_no, patient_id, doctor_id, appt_date, appt_time, type,
				                         notes, rescheduled_from_id, created_by)
				values (:c, :no, :p, :d, :date, :time, :type, :notes, :from, :user)
				returning id
				""")
				.param("c", clinicId).param("no", no).param("p", patientId).param("d", doctorId)
				.param("date", date).param("time", time).param("type", type)
				.param("notes", blankToNull(notes)).param("from", rescheduledFrom).param("user", currentUser.id())
				.query(Long.class).single();
		events.publish(clinicId, EventBus.Type.APPOINTMENTS);
		return get(id);
	}

	@Transactional
	public AppointmentDto markArrived(long id) {
		requireOpenForChange(id);
		jdbc.sql("update appointment set status = 'arrived', updated_at = now() where id = :id")
				.param("id", id).update();
		events.publish(currentUser.clinicId(), EventBus.Type.APPOINTMENTS);
		return get(id);
	}

	@Transactional
	public AppointmentDto cancel(long id, String reason) {
		requireOpenForChange(id);
		jdbc.sql("update appointment set status = 'cancelled', cancellation_reason = :reason, updated_at = now() where id = :id")
				.param("id", id).param("reason", blankToNull(reason)).update();
		events.publish(currentUser.clinicId(), EventBus.Type.APPOINTMENTS);
		return get(id);
	}

	@Transactional
	public AppointmentDto markNoShow(long id) {
		requireOpenForChange(id);
		jdbc.sql("update appointment set status = 'no-show', updated_at = now() where id = :id")
				.param("id", id).update();
		events.publish(currentUser.clinicId(), EventBus.Type.APPOINTMENTS);
		return get(id);
	}

	/**
	 * Keeps the original (status 'rescheduled') for history and books a new one linked back to it. The old
	 * row is released first so the same slot can be reused on a different day.
	 */
	@Transactional
	public AppointmentDto reschedule(long id, RescheduleRequest r) {
		Locked old = requireOpenForChange(id);
		if (r.date().isBefore(clock.today())) {
			throw ApiException.unprocessable("APPOINTMENT_IN_PAST", "An appointment cannot be moved into the past.");
		}
		ensureSlotFree(old.doctorId(), r.date(), r.time(), id);
		jdbc.sql("update appointment set status = 'rescheduled', updated_at = now() where id = :id")
				.param("id", id).update();
		return insert(old.patientId(), old.doctorId(), r.date(), r.time(), old.type(),
				r.notes() != null ? r.notes() : old.notes(), id);
	}

	private record Locked(String status, long patientId, long doctorId, String type, String notes) {
	}

	/** Locks the row and checks it can still be changed (not checked in, cancelled, done ...). */
	private Locked requireOpenForChange(long id) {
		Locked row = jdbc.sql("""
				select status, patient_id, doctor_id, type, notes from appointment
				where id = :id and clinic_id = :c for update
				""").param("id", id).param("c", currentUser.clinicId())
				.query((rs, i) -> new Locked(rs.getString("status"), rs.getLong("patient_id"), rs.getLong("doctor_id"),
						rs.getString("type"), rs.getString("notes")))
				.optional()
				.orElseThrow(() -> ApiException.notFound("APPOINTMENT_NOT_FOUND", "No such appointment."));
		if (!row.status().equals("scheduled") && !row.status().equals("arrived")) {
			throw ApiException.unprocessable("APPOINTMENT_NOT_CHANGEABLE",
					"This appointment is already " + row.status().replace('-', ' ') + " and can no longer be changed.");
		}
		return row;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private RowMapper<AppointmentDto> mapper() {
		return (rs, i) -> {
			long no = rs.getLong("appointment_no");
			long patientNo = rs.getLong("patient_no");
			long visitId = rs.getLong("visit_id");
			boolean hasVisit = !rs.wasNull();
			int token = rs.getInt("token_no");
			String status = rs.getString("status");
			long toId = rs.getLong("rescheduled_to_id");
			Long rescheduledTo = rs.wasNull() ? null : toId;
			long fromId = rs.getLong("rescheduled_from_id");
			Long rescheduledFrom = rs.wasNull() ? null : fromId;
			return new AppointmentDto(rs.getLong("id"), no, "A" + String.format("%05d", no), rs.getLong("patient_id"),
					rs.getString("patient_name"), "P" + String.format("%05d", patientNo), rs.getString("mobile"),
					rs.getLong("doctor_id"), rs.getString("doctor_name"), rs.getObject("appt_date", LocalDate.class),
					rs.getObject("appt_time", LocalTime.class).toString(), rs.getString("type"), status,
					rs.getString("notes"), rs.getString("cancellation_reason"), rescheduledFrom, rescheduledTo,
					rs.getString("reminder_status"), hasVisit ? visitId : null,
					hasVisit ? String.format("%02d", token) : null, rs.getString("visit_status"),
					status.equals("scheduled") || status.equals("arrived"));
		};
	}
}
