package com.preclinic.backend.consultation;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import com.preclinic.backend.consultation.ConsultationDtos.ConsultationContext;
import com.preclinic.backend.consultation.ConsultationDtos.ConsultationDto;
import com.preclinic.backend.consultation.ConsultationDtos.ConsultationRequest;
import com.preclinic.backend.consultation.ConsultationDtos.ConsultationStats;
import com.preclinic.backend.consultation.ConsultationDtos.InvestigationResultRequest;
import com.preclinic.backend.consultation.ConsultationDtos.TemplateDto;
import com.preclinic.backend.consultation.ConsultationDtos.TemplateRequest;

import jakarta.validation.Valid;

/**
 * Consultations and prescriptions. Everything that records or changes clinical content is the
 * doctor's; staff may read consultations (the assistant prints prescriptions and sees visit history).
 */
@RestController
@RequestMapping(Api.V1)
public class ConsultationController {

	private final ConsultationService consultations;
	private final TemplateService templates;

	public ConsultationController(ConsultationService consultations, TemplateService templates) {
		this.consultations = consultations;
		this.templates = templates;
	}

	/** Opens the consultation room for a queue entry: patient, vitals, history, any saved draft, next in line. */
	@GetMapping("/visits/{visitId}/consultation-context")
	@PreAuthorize("hasRole('DOCTOR')")
	public ConsultationContext context(@PathVariable long visitId) {
		return consultations.context(visitId);
	}

	/**
	 * Saves the whole consultation (replaces what was stored). {@code complete=true} (the default)
	 * also finishes the visit in the queue, as Print / Print and Next do; {@code complete=false}
	 * keeps a draft while the patient is still with the doctor.
	 */
	@PutMapping("/visits/{visitId}/consultation")
	@PreAuthorize("hasRole('DOCTOR')")
	public ConsultationDto save(@PathVariable long visitId, @Valid @RequestBody ConsultationRequest request,
			@RequestParam(defaultValue = "true") boolean complete) {
		return consultations.save(visitId, request, complete);
	}

	@GetMapping("/consultations")
	public PageResult<ConsultationDto> list(
			@RequestParam(required = false) Long patientId,
			@RequestParam(required = false) Long doctorId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Boolean hasPrescription,
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String sort,
			@RequestParam(required = false) String dir,
			@RequestParam(defaultValue = "0") int skip,
			@RequestParam(defaultValue = "10") int limit) {
		return consultations.list(new ConsultationService.Query(patientId, doctorId, null, from, to, hasPrescription, q,
				sort, dir, Math.max(skip, 0), Math.min(Math.max(limit, 1), 200)));
	}

	@GetMapping("/consultations/stats")
	public ConsultationStats stats() {
		return consultations.stats();
	}

	@GetMapping("/consultations/{id}")
	public ConsultationDto get(@PathVariable long id) {
		return consultations.get(id);
	}

	/** Records the outcome of an ordered investigation. */
	@PatchMapping("/consultations/{id}/investigations/{orderId}")
	@PreAuthorize("hasRole('DOCTOR')")
	public ConsultationDto investigationResult(@PathVariable long id, @PathVariable long orderId,
			@Valid @RequestBody InvestigationResultRequest request) {
		return consultations.updateInvestigation(id, orderId, request);
	}

	// ----- prescription templates -----

	@GetMapping("/prescription-templates")
	public List<TemplateDto> templates() {
		return templates.list();
	}

	@PostMapping("/prescription-templates")
	@PreAuthorize("hasRole('DOCTOR')")
	@ResponseStatus(HttpStatus.CREATED)
	public TemplateDto createTemplate(@Valid @RequestBody TemplateRequest request) {
		return templates.create(request);
	}

	@DeleteMapping("/prescription-templates/{id}")
	@PreAuthorize("hasRole('DOCTOR')")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteTemplate(@PathVariable long id) {
		templates.delete(id);
	}
}
