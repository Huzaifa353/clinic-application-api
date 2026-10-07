package com.preclinic.backend.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.preclinic.backend.common.ApiException;
import com.preclinic.backend.security.CurrentUser;

/**
 * Resolves "which doctor" for screens that both roles use (doctor status, print header). Today a
 * clinic has one doctor, so an assistant asking without an id gets that doctor.
 */
@Service
public class DoctorDirectory {

	private final AppUserRepository users;
	private final CurrentUser currentUser;

	public DoctorDirectory(AppUserRepository users, CurrentUser currentUser) {
		this.users = users;
		this.currentUser = currentUser;
	}

	/** The requested doctor; or the caller if they are a doctor; or the clinic's first active doctor. */
	@Transactional(readOnly = true)
	public AppUser resolve(Long requestedDoctorId) {
		long clinicId = currentUser.clinicId();
		if (requestedDoctorId != null) {
			return users.findByIdAndClinicId(requestedDoctorId, clinicId)
					.filter(u -> u.getRole() == Role.DOCTOR && u.isActive())
					.orElseThrow(() -> ApiException.notFound("DOCTOR_NOT_FOUND", "No such doctor."));
		}
		if (currentUser.isDoctor()) {
			return users.findByIdAndClinicId(currentUser.id(), clinicId)
					.orElseThrow(() -> ApiException.notFound("DOCTOR_NOT_FOUND", "No such doctor."));
		}
		return users.findByClinicIdAndRoleAndActiveTrueOrderByIdAsc(clinicId, Role.DOCTOR).stream()
				.findFirst()
				.orElseThrow(() -> ApiException.notFound("DOCTOR_NOT_FOUND", "This clinic has no active doctor."));
	}
}
