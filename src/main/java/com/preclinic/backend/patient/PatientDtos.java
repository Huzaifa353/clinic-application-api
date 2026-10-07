package com.preclinic.backend.patient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class PatientDtos {

	private PatientDtos() {
	}

	/**
	 * A patient with the figures every screen shows next to them. Visit counts, last visit and
	 * outstanding balance are computed from visits and invoices, never stored on the patient.
	 * {@code followUp} is None / Upcoming / Due / Overdue for the nearest open follow-up.
	 */
	public record PatientDto(long id, long patientNo, String displayId, String name, String mobile, String gender,
			Integer age, LocalDate dateOfBirth, String cnic, String bloodGroup, String address, Long photoFileId,
			List<String> allergies, List<String> medicalHistory, List<String> surgicalHistory,
			List<String> currentMedications, LocalDate registrationDate, long totalVisits, LocalDate lastVisit,
			String lastDoctor, BigDecimal outstandingBalance, String followUp, LocalDate nextFollowUpDate) {
	}

	public record PatientStats(long total, long newThisMonth, long active, long followUpsDue) {
	}

	public record CreatePatientRequest(
			@NotBlank @Size(max = 200) String name,
			@NotBlank @Size(max = 30) String mobile,
			@Pattern(regexp = "Male|Female|Other") String gender,
			@Min(0) @Max(150) Integer age,
			@Past LocalDate dateOfBirth,
			@Size(max = 20) String cnic,
			@Pattern(regexp = "|(A|B|AB|O)[+-]", message = "must be a blood group such as B+") String bloodGroup,
			@Size(max = 500) String address,
			@Size(max = 100) List<@NotBlank @Size(max = 300) String> allergies) {
	}

	/** Demographics only; a null field is left unchanged, an empty string clears an optional text field. */
	public record UpdatePatientRequest(
			@Size(min = 1, max = 200) String name,
			@Size(min = 1, max = 30) String mobile,
			@Pattern(regexp = "Male|Female|Other") String gender,
			@Min(0) @Max(150) Integer age,
			@Past LocalDate dateOfBirth,
			@Size(max = 20) String cnic,
			@Pattern(regexp = "|(A|B|AB|O)[+-]", message = "must be a blood group such as B+") String bloodGroup,
			@Size(max = 500) String address) {
	}

	/** The doctor's editable clinical lists; each replaces the stored list as a whole. */
	public record ClinicalListsRequest(
			@NotNull @Size(max = 100) List<@NotBlank @Size(max = 300) String> allergies,
			@NotNull @Size(max = 100) List<@NotBlank @Size(max = 300) String> medicalHistory,
			@NotNull @Size(max = 100) List<@NotBlank @Size(max = 300) String> surgicalHistory,
			@NotNull @Size(max = 100) List<@NotBlank @Size(max = 300) String> currentMedications) {
	}
}
