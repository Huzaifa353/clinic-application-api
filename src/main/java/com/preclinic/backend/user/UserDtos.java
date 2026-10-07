package com.preclinic.backend.user;

import java.time.Instant;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class UserDtos {

	private UserDtos() {
	}

	/** Lightweight user for pickers (e.g. choosing the doctor of an appointment). */
	public record UserSummary(long id, String fullName, String role) {
	}

	public record DoctorProfileDto(long userId, String name, String qualifications, String specialization,
			String registrationNo, String addressLine1, String addressLine2, String city, String state, String zip,
			String country, String preferredPrintLanguage) {
	}

	/**
	 * Full replacement of the editable profile fields (a null clears the field). The exception is
	 * {@code name}, which is kept when omitted because a doctor cannot have no name.
	 */
	public record UpdateDoctorProfileRequest(
			@Size(max = 200) String name,
			@Size(max = 300) String qualifications,
			@Size(max = 200) String specialization,
			@Size(max = 100) String registrationNo,
			@Size(max = 300) String addressLine1,
			@Size(max = 300) String addressLine2,
			@Size(max = 100) String city,
			@Size(max = 100) String state,
			@Size(max = 20) String zip,
			@Size(max = 100) String country,
			@Pattern(regexp = "en|ur|bilingual", message = "must be en, ur or bilingual") String preferredPrintLanguage) {
	}

	/**
	 * {@code away} is the doctor's manual flag. {@code consulting*} say who the doctor is with right now
	 * (derived from the queue, null when nobody), so the assistant's status card needs only this call.
	 */
	public record DoctorStatusDto(long doctorId, String doctorName, boolean away, Instant updatedAt,
			Long consultingVisitId, String consultingToken, String consultingPatientName) {
	}

	public record UpdateDoctorStatusRequest(@NotNull Boolean away) {
	}
}
