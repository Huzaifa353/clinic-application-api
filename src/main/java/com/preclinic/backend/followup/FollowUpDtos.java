package com.preclinic.backend.followup;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class FollowUpDtos {

	private FollowUpDtos() {
	}

	/**
	 * One follow-up with the patient and original visit it came from. {@code state} (Upcoming / Due /
	 * Overdue while open; Completed / Cancelled once resolved) and {@code daysOverdue} are derived from
	 * the date every time, never stored. {@code dueDate} is the effective date (a reschedule overrides
	 * the original); {@code remindedToday} drives the dashboard's "already reminded" mark.
	 */
	public record FollowUpItemDto(long id, long consultationId, String consultationDisplayId, Long visitId,
			LocalDate visitDate, long patientId, String patientDisplayId, String patientName, String mobile,
			long doctorId, String doctorName, List<String> diagnoses, int days, String reason, LocalDate dueDate,
			LocalDate originalDueDate, String state, long daysOverdue, String status, String contactStatus,
			LocalDate lastRemindedOn, boolean remindedToday, Long linkedAppointmentId, LocalDate linkedAppointmentDate,
			String linkedAppointmentTime, String linkedAppointmentStatus, String cancellationReason,
			Instant resolvedAt) {
	}

	public record FollowUpStats(long dueToday, long overdue, long upcoming, long completed) {
	}

	public record ContactRequest(@NotNull @Pattern(regexp = "contacted|no-response|declined") String status) {
	}

	public record RescheduleRequest(@NotNull LocalDate date) {
	}

	public record CancelRequest(@Size(max = 500) String reason) {
	}

	/** Books the follow-up visit as a real appointment and links it to the follow-up. */
	public record ScheduleRequest(@NotNull LocalDate date, @NotNull LocalTime time, Long doctorId,
			@Size(max = 1000) String notes) {
	}
}
