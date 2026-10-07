package com.preclinic.backend.queue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class QueueDtos {

	private QueueDtos() {
	}

	/** Vitals taken at the front desk; every field is optional. Temperature is in °F, weight in kg. */
	public record VitalsDto(
			@Min(40) @Max(300) Integer bpSystolic,
			@Min(20) @Max(200) Integer bpDiastolic,
			@DecimalMin("80") @DecimalMax("115") BigDecimal temperature,
			@Min(20) @Max(300) Integer pulse,
			@DecimalMin("0.5") @DecimalMax("500") BigDecimal weight,
			@Min(50) @Max(100) Integer spo2) {
	}

	/** The visit's invoice as the queue and dashboards show it (fee, discount, paid, status, method). */
	public record QueueBilling(long invoiceId, String invoiceDisplayId, BigDecimal consultationFee,
			BigDecimal additionalCharges, BigDecimal discount, BigDecimal total, BigDecimal paid, BigDecimal balance,
			String paymentStatus, String paymentMethod) {
	}

	/**
	 * One patient's place in a day's queue (a "visit"). {@code tokenNo} is unique only within its day.
	 * {@code id} is what every action uses; {@code hasConsultation} says whether a consultation was recorded.
	 */
	public record QueueItemDto(long id, String tokenNo, LocalDate queueDate, long patientId, String patientDisplayId,
			String patientName, String mobile, String gender, Integer age, String status, boolean urgent,
			String source, Long appointmentId, int sortOrder, VitalsDto vitals, Instant checkedInAt,
			Instant consultStartedAt, Instant completedAt, Long doctorId, String doctorName, boolean hasConsultation,
			QueueBilling billing) {
	}

	public record QueueStats(long total, long waiting, long consulting, long hold, long completed, long skipped,
			long cancelled, BigDecimal collected) {
	}

	/** Everything the assistant enters at the front desk for one patient ("Add to Queue"). */
	public record IntakeRequest(
			@NotNull Long patientId,
			Long appointmentId,
			@Valid VitalsDto vitals,
			Long serviceId,
			@Size(max = 300) String description,
			@DecimalMin("0") @DecimalMax("100000000") BigDecimal consultationFee,
			@DecimalMin("0") @DecimalMax("100000000") BigDecimal additionalCharges,
			@DecimalMin("0") @DecimalMax("100000000") BigDecimal discount,
			@DecimalMin("0") @DecimalMax("100000000") BigDecimal amountPaid,
			@Pattern(regexp = "Cash|Card|Bank Transfer|EasyPaisa|JazzCash") String paymentMethod,
			@Size(max = 200) String reference,
			/** Optional; leaving it out means not urgent. */
			Boolean urgent) {
	}

	public record StatusRequest(@NotNull @Pattern(regexp = "waiting|consulting|completed|hold|skipped|cancelled") String status) {
	}

	public record UrgentRequest(@NotNull Boolean urgent) {
	}

	public record OrderRequest(@NotEmpty @Size(max = 1000) List<@NotNull Long> orderedIds) {
	}
}
