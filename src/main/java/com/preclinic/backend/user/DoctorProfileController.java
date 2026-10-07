package com.preclinic.backend.user;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.preclinic.backend.common.Api;
import com.preclinic.backend.user.UserDtos.DoctorProfileDto;
import com.preclinic.backend.user.UserDtos.UpdateDoctorProfileRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping(Api.V1 + "/doctor-profile")
public class DoctorProfileController {

	private final DoctorProfileService service;

	public DoctorProfileController(DoctorProfileService service) {
		this.service = service;
	}

	/** Both roles can read it (assistants need the doctor's name and registration for printed documents). */
	@GetMapping
	public DoctorProfileDto get(@RequestParam(required = false) Long doctorId) {
		return service.get(doctorId);
	}

	@PutMapping
	@PreAuthorize("hasRole('DOCTOR')")
	public DoctorProfileDto update(@Valid @RequestBody UpdateDoctorProfileRequest request) {
		return service.updateMine(request);
	}
}
