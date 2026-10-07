package com.preclinic.backend.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.preclinic.backend.common.ClinicClock;
import com.preclinic.backend.event.EventBus;
import com.preclinic.backend.security.CurrentUser;
import com.preclinic.backend.user.UserDtos.DoctorStatusDto;

@Service
public class DoctorStatusService {

	private final DoctorDirectory directory;
	private final DoctorStatusRepository statuses;
	private final CurrentUser currentUser;
	private final ClinicClock clock;
	private final JdbcClient jdbc;
	private final EventBus events;

	public DoctorStatusService(DoctorDirectory directory, DoctorStatusRepository statuses, CurrentUser currentUser,
			ClinicClock clock, JdbcClient jdbc, EventBus events) {
		this.directory = directory;
		this.statuses = statuses;
		this.currentUser = currentUser;
		this.clock = clock;
		this.jdbc = jdbc;
		this.events = events;
	}

	/** A doctor who never set the flag is simply "not away". */
	@Transactional(readOnly = true)
	public DoctorStatusDto get(Long doctorId) {
		AppUser doctor = directory.resolve(doctorId);
		DoctorStatus status = statuses.findById(doctor.getId())
				.orElseGet(() -> new DoctorStatus(doctor.getId(), clock.now()));
		return toDto(doctor, status);
	}

	@Transactional
	public DoctorStatusDto setMine(boolean away) {
		AppUser doctor = directory.resolve(currentUser.id());
		DoctorStatus status = statuses.findById(doctor.getId())
				.orElseGet(() -> new DoctorStatus(doctor.getId(), clock.now()));
		status.setAway(away);
		status.setUpdatedAt(clock.now());
		DoctorStatus saved = statuses.save(status);
		events.publish(currentUser.clinicId(), EventBus.Type.DOCTOR_STATUS);
		return toDto(doctor, saved);
	}

	private DoctorStatusDto toDto(AppUser doctor, DoctorStatus status) {
		var current = jdbc.sql("""
				select v.id, v.token_no, p.name from visit v join patient p on p.id = v.patient_id
				where v.doctor_id = :d and v.status = 'consulting' order by v.id desc limit 1
				""").param("d", doctor.getId())
				.query((rs, i) -> new Object[] { rs.getLong("id"), String.format("%02d", rs.getInt("token_no")),
						rs.getString("name") })
				.optional().orElse(null);
		return new DoctorStatusDto(doctor.getId(), doctor.getFullName(), status.isAway(), status.getUpdatedAt(),
				current == null ? null : (Long) current[0], current == null ? null : (String) current[1],
				current == null ? null : (String) current[2]);
	}
}
