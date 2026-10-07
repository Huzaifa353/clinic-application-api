package com.preclinic.backend.notification;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.preclinic.backend.common.ApiException;
import com.preclinic.backend.common.ClinicClock;
import com.preclinic.backend.event.EventBus;
import com.preclinic.backend.security.CurrentUser;

/**
 * Cross-role notifications (today: "the doctor has started a consultation" shown to the assistant).
 * Like the frontend, only today's notifications are shown.
 */
@Service
public class NotificationService {

	public record NotificationDto(long id, String type, Long visitId, String tokenNo, String patientName,
			String message, java.time.Instant createdAt, boolean read) {
	}

	private static final String SELECT = """
			select n.id, n.type, n.visit_id, v.token_no, p.name as patient_name, n.message, n.created_at, n.read_at
			from notification n
			left join visit v on v.id = n.visit_id
			left join patient p on p.id = v.patient_id
			""";

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ClinicClock clock;
	private final EventBus events;

	public NotificationService(JdbcClient jdbc, CurrentUser currentUser, ClinicClock clock, EventBus events) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.clock = clock;
		this.events = events;
	}

	/** Tells the assistant a consultation has begun. Runs inside the status-change transaction. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void consultationStarted(long visitId, String doctorName, String patientName, int tokenNo) {
		String message = doctorName + " has started the consultation for " + patientName + " (Token #"
				+ String.format("%02d", tokenNo) + "). Please send the patient to the consultation room.";
		jdbc.sql("""
				insert into notification (clinic_id, type, target_role, visit_id, message)
				values (:c, 'CONSULTATION_STARTED', 'assistant', :v, :message)
				""").param("c", currentUser.clinicId()).param("v", visitId).param("message", message).update();
		events.publish(currentUser.clinicId(), EventBus.Type.NOTIFICATION);
	}

	@Transactional(readOnly = true)
	public List<NotificationDto> list(boolean unreadOnly) {
		return jdbc.sql(SELECT + """
				where n.clinic_id = :c and n.target_role = :role and n.created_at >= :dayStart
				  and (:all or n.read_at is null)
				order by n.created_at desc, n.id desc
				""")
				.param("c", currentUser.clinicId()).param("role", currentUser.role().dbValue())
				.param("dayStart", startOfToday()).param("all", !unreadOnly)
				.query((rs, i) -> toDto(rs)).list();
	}

	@Transactional(readOnly = true)
	public long unreadCount() {
		return jdbc.sql("""
				select count(*) from notification
				where clinic_id = :c and target_role = :role and created_at >= :dayStart and read_at is null
				""")
				.param("c", currentUser.clinicId()).param("role", currentUser.role().dbValue())
				.param("dayStart", startOfToday()).query(Long.class).single();
	}

	@Transactional
	public void markRead(long id) {
		int changed = jdbc.sql("""
				update notification set read_at = coalesce(read_at, now())
				where id = :id and clinic_id = :c and target_role = :role
				""")
				.param("id", id).param("c", currentUser.clinicId()).param("role", currentUser.role().dbValue()).update();
		if (changed == 0) {
			throw ApiException.notFound("NOTIFICATION_NOT_FOUND", "No such notification.");
		}
		events.publish(currentUser.clinicId(), EventBus.Type.NOTIFICATION);
	}

	@Transactional
	public void markAllRead() {
		jdbc.sql("""
				update notification set read_at = now()
				where clinic_id = :c and target_role = :role and read_at is null and created_at >= :dayStart
				""")
				.param("c", currentUser.clinicId()).param("role", currentUser.role().dbValue())
				.param("dayStart", startOfToday()).update();
		events.publish(currentUser.clinicId(), EventBus.Type.NOTIFICATION);
	}

	private OffsetDateTime startOfToday() {
		return ZonedDateTime.of(clock.today().atStartOfDay(), clock.zone()).toOffsetDateTime();
	}

	private static NotificationDto toDto(java.sql.ResultSet rs) throws java.sql.SQLException {
		long visitId = rs.getLong("visit_id");
		boolean hasVisit = !rs.wasNull();
		int token = rs.getInt("token_no");
		return new NotificationDto(rs.getLong("id"), rs.getString("type"), hasVisit ? visitId : null,
				hasVisit ? String.format("%02d", token) : null, rs.getString("patient_name"), rs.getString("message"),
				rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getObject("read_at") != null);
	}
}
