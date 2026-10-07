package com.preclinic.backend.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.preclinic.backend.common.ApiException;
import com.preclinic.backend.security.CurrentUser;
import com.preclinic.backend.user.UserDtos.DoctorProfileDto;
import com.preclinic.backend.user.UserDtos.UpdateDoctorProfileRequest;

@Service
public class DoctorProfileService {

	private final DoctorDirectory directory;
	private final DoctorProfileRepository profiles;
	private final AppUserRepository users;
	private final CurrentUser currentUser;

	public DoctorProfileService(DoctorDirectory directory, DoctorProfileRepository profiles, AppUserRepository users,
			CurrentUser currentUser) {
		this.directory = directory;
		this.profiles = profiles;
		this.users = users;
		this.currentUser = currentUser;
	}

	@Transactional(readOnly = true)
	public DoctorProfileDto get(Long doctorId) {
		AppUser doctor = directory.resolve(doctorId);
		DoctorProfile profile = profiles.findById(doctor.getId()).orElseGet(() -> new DoctorProfile(doctor.getId()));
		return toDto(doctor, profile);
	}

	/** A doctor edits their own profile; the row is created on first save. */
	@Transactional
	public DoctorProfileDto updateMine(UpdateDoctorProfileRequest request) {
		AppUser doctor = users.findByIdAndClinicId(currentUser.id(), currentUser.clinicId())
				.orElseThrow(() -> ApiException.notFound("DOCTOR_NOT_FOUND", "No such doctor."));
		DoctorProfile profile = profiles.findById(doctor.getId()).orElseGet(() -> new DoctorProfile(doctor.getId()));

		if (request.name() != null && !request.name().isBlank()) {
			doctor.setFullName(request.name().trim());
		}
		profile.setQualifications(blankToNull(request.qualifications()));
		profile.setSpecialization(blankToNull(request.specialization()));
		profile.setRegistrationNo(blankToNull(request.registrationNo()));
		profile.setAddressLine1(blankToNull(request.addressLine1()));
		profile.setAddressLine2(blankToNull(request.addressLine2()));
		profile.setCity(blankToNull(request.city()));
		profile.setState(blankToNull(request.state()));
		profile.setZip(blankToNull(request.zip()));
		profile.setCountry(blankToNull(request.country()));
		profile.setPreferredPrintLanguage(blankToNull(request.preferredPrintLanguage()));
		return toDto(doctor, profiles.save(profile));
	}

	private static DoctorProfileDto toDto(AppUser doctor, DoctorProfile p) {
		return new DoctorProfileDto(doctor.getId(), doctor.getFullName(), p.getQualifications(), p.getSpecialization(),
				p.getRegistrationNo(), p.getAddressLine1(), p.getAddressLine2(), p.getCity(), p.getState(), p.getZip(),
				p.getCountry(), p.getPreferredPrintLanguage());
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
