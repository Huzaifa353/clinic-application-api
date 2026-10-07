package com.preclinic.backend.user;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.preclinic.backend.common.Api;
import com.preclinic.backend.user.UserDtos.DoctorStatusDto;
import com.preclinic.backend.user.UserDtos.UpdateDoctorStatusRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping(Api.V1 + "/doctor-status")
public class DoctorStatusController {

	private final DoctorStatusService service;

	public DoctorStatusController(DoctorStatusService service) {
		this.service = service;
	}

	/** The assistant dashboard shows this; both roles may read it. */
	@GetMapping
	public DoctorStatusDto get(@RequestParam(required = false) Long doctorId) {
		return service.get(doctorId);
	}

	/** Only the doctor toggles their own away flag. */
	@PutMapping
	@PreAuthorize("hasRole('DOCTOR')")
	public DoctorStatusDto set(@Valid @RequestBody UpdateDoctorStatusRequest request) {
		return service.setMine(request.away());
	}
}
