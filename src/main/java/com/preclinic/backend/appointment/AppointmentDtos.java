package com.preclinic.backend.appointment;

import java.time.LocalDate;
import java.time.LocalTime;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AppointmentDtos {

	private AppointmentDtos() {
	}

	/**
	 * {@code time} is "HH:mm" (24h). {@code visitId/queueTokenNo/visitStatus} describe the queue entry the
	 * appointment was checked in to, so screens can show Waiting / In Consultation / Completed without
	 * a second call. {@code reschedulable} tells the UI whether Reschedule/Cancel/No-show still apply.
	 */
	public record AppointmentDto(long id, long appointmentNo, String displayId, long patientId, String patientName,
			String patientDisplayId, String mobile, long doctorId, String doctorName, LocalDate date, String time,
			String type, String status, String notes, String cancellationReason, Long rescheduledFromId,
			Long rescheduledToId, String reminderStatus, Long visitId, String queueTokenNo, String visitStatus,
			boolean reschedulable) {
	}

	public record AppointmentStats(long total, long scheduled, long completed, long cancelled, long noShow) {
	}

	public record CreateAppointmentRequest(
			@NotNull Long patientId,
			Long doctorId,
			@NotNull LocalDate date,
			@NotNull LocalTime time,
			@Pattern(regexp = "Consultation|Follow-up|New Patient|Review") String type,
			@Size(max = 1000) String notes) {
	}

	public record CancelRequest(@Size(max = 500) String reason) {
	}

	public record RescheduleRequest(@NotNull LocalDate date, @NotNull LocalTime time, @Size(max = 1000) String notes) {
	}

	/** What would collide if this appointment were booked; both null means it is free to book. */
	public record ConflictsDto(AppointmentDto patientSameDay, AppointmentDto doctorSlot) {
	}
}
