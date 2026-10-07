package com.preclinic.backend.consultation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.preclinic.backend.patient.PatientDtos.PatientDto;
import com.preclinic.backend.queue.QueueDtos.QueueItemDto;
import com.preclinic.backend.queue.QueueDtos.VitalsDto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class ConsultationDtos {

	private ConsultationDtos() {
	}

	// ---------- responses ----------

	public record ExaminationFindingDto(Long id, String category, String finding, String customFinding) {
	}

	public record InvestigationOrderDto(Long id, String investigationId, String investigationName, boolean isCustom,
			String status, String resultNote) {
	}

	/** {@code instructions} is the meal timing ("After Meals"), named as the frontend's PrescriptionItem calls it. */
	public record PrescriptionItemDto(Long id, String medicine, Long productId, String frequency, String duration,
			String instructions, String notes) {
	}

	/**
	 * {@code dueDate} is the effective due date (a reschedule overrides the original). {@code state} is
	 * derived every time: Upcoming / Due / Overdue while open, Completed / Cancelled once resolved.
	 */
	public record FollowUpDto(long id, int days, String reason, LocalDate dueDate, LocalDate originalDueDate,
			String state, String status, String contactStatus, LocalDate lastRemindedOn, Long linkedAppointmentId,
			String cancellationReason, Instant resolvedAt) {
	}

	public record ConsultationDto(long id, long consultNo, String displayId, long visitId, String tokenNo,
			LocalDate visitDate, long patientId, String patientName, String patientDisplayId, long doctorId,
			String doctorName, String notes, List<String> symptoms, List<String> diagnoses,
			List<ExaminationFindingDto> examinationFindings, List<InvestigationOrderDto> investigationOrders,
			FollowUpDto followUp, List<PrescriptionItemDto> prescription, VitalsDto vitals, Instant createdAt,
			Instant updatedAt) {
	}

	/** Everything the consultation room needs to open, in one call. */
	public record ConsultationContext(QueueItemDto visit, PatientDto patient, ConsultationDto consultation,
			List<ConsultationDto> history, QueueItemDto nextWaiting) {
	}

	public record ConsultationStats(long today, long thisWeek, long doctors) {
	}

	// ---------- requests ----------

	public record ExaminationFindingInput(
			@NotBlank @Size(max = 100) String category,
			@NotBlank @Size(max = 200) String finding,
			@Size(max = 300) String customFinding) {
	}

	public record InvestigationOrderInput(
			@Size(max = 100) String investigationId,
			@NotBlank @Size(max = 200) String investigationName,
			Boolean isCustom,
			@Pattern(regexp = "Ordered|Completed") String status,
			@Size(max = 1000) String resultNote) {
	}

	public record PrescriptionItemInput(
			@NotBlank @Size(max = 300) String medicine,
			Long productId,
			@Size(max = 50) String frequency,
			@Size(max = 50) String duration,
			@Size(max = 100) String instructions,
			@Size(max = 500) String notes) {
	}

	public record FollowUpInput(
			Boolean enabled,
			@Min(0) @Max(365) Integer days,
			@Size(max = 300) String reason) {
	}

	/** The doctor's whole consultation document; saving it again replaces what was stored. */
	public record ConsultationRequest(
			@Size(max = 100) List<@NotBlank @Size(max = 200) String> symptoms,
			@Size(max = 100) List<@NotBlank @Size(max = 200) String> diagnoses,
			@Size(max = 5000) String notes,
			@Size(max = 200) List<@Valid ExaminationFindingInput> examinationFindings,
			@Size(max = 100) List<@Valid InvestigationOrderInput> investigationOrders,
			@Valid FollowUpInput followUp,
			@Size(max = 100) List<@Valid PrescriptionItemInput> prescription) {
	}

	public record InvestigationResultRequest(
			@NotNull @Pattern(regexp = "Ordered|Completed") String status,
			@Size(max = 1000) String resultNote) {
	}

	// ---------- templates ----------

	public record TemplateDto(long id, String name, long doctorId, String doctorName, List<PrescriptionItemDto> medicines) {
	}

	public record TemplateRequest(
			@NotBlank @Size(max = 200) String name,
			@NotEmpty @Size(max = 50) List<@Valid PrescriptionItemInput> medicines) {
	}
}
