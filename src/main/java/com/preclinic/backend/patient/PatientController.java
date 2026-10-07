package com.preclinic.backend.patient;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.preclinic.backend.common.Api;
import com.preclinic.backend.common.PageResult;
import com.preclinic.backend.patient.PatientDtos.ClinicalListsRequest;
import com.preclinic.backend.patient.PatientDtos.CreatePatientRequest;
import com.preclinic.backend.patient.PatientDtos.PatientDto;
import com.preclinic.backend.patient.PatientDtos.PatientStats;
import com.preclinic.backend.patient.PatientDtos.UpdatePatientRequest;

import jakarta.validation.Valid;

/**
 * Patients. Both roles register, search and edit demographics; only the doctor edits the clinical
 * lists (allergies, histories, current medications).
 */
@RestController
@RequestMapping(Api.V1 + "/patients")
public class PatientController {

	private final PatientService patients;
	private final TimelineService timeline;

	public PatientController(PatientService patients, TimelineService timeline) {
		this.patients = patients;
		this.timeline = timeline;
	}

	/** The Patients screen: filters, sort and paging done on the server. */
	@GetMapping
	public PageResult<PatientDto> list(
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String gender,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String followUp,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate registeredFrom,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate registeredTo,
			@RequestParam(required = false) String sort,
			@RequestParam(required = false) String dir,
			@RequestParam(defaultValue = "0") int skip,
			@RequestParam(defaultValue = "10") int limit) {
		return patients.list(new PatientService.Query(q, gender, status, followUp, registeredFrom, registeredTo, sort,
				dir, Math.max(skip, 0), Math.min(Math.max(limit, 1), 100)));
	}

	/** Quick search used by the dashboards: name, patient no., mobile in any format, CNIC. */
	@GetMapping("/search")
	public List<PatientDto> search(@RequestParam String q, @RequestParam(defaultValue = "20") int limit) {
		return patients.search(q, Math.min(Math.max(limit, 1), 50));
	}

	/** Patients already registered under this mobile number (drives the "register anyway?" warning). */
	@GetMapping("/duplicates")
	public List<PatientDto> duplicates(@RequestParam String mobile) {
		return patients.duplicates(mobile);
	}

	@GetMapping("/stats")
	public PatientStats stats() {
		return patients.stats();
	}

	/** The patient's history as one feed (consultations, payments, follow-up outcomes, documents), newest first. */
	@GetMapping("/{id}/timeline")
	public List<TimelineService.TimelineEvent> timeline(@PathVariable long id,
			@RequestParam(defaultValue = "all") String filter, @RequestParam(defaultValue = "0") int skip,
			@RequestParam(defaultValue = "100") int limit) {
		return timeline.timeline(id, filter, Math.max(skip, 0), Math.min(Math.max(limit, 1), 500));
	}

	@GetMapping("/{id}")
	public PatientDto get(@PathVariable long id) {
		return patients.get(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public PatientDto create(@Valid @RequestBody CreatePatientRequest request) {
		return patients.create(request);
	}

	@PatchMapping("/{id}")
	public PatientDto update(@PathVariable long id, @Valid @RequestBody UpdatePatientRequest request) {
		return patients.update(id, request);
	}

	@PutMapping("/{id}/clinical")
	@PreAuthorize("hasRole('DOCTOR')")
	public PatientDto updateClinical(@PathVariable long id, @Valid @RequestBody ClinicalListsRequest request) {
		return patients.updateClinical(id, request);
	}
}
