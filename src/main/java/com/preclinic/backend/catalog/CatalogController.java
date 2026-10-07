package com.preclinic.backend.catalog;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.preclinic.backend.catalog.CatalogDtos.CatalogBootstrap;
import com.preclinic.backend.catalog.CatalogDtos.CatalogEntryDto;
import com.preclinic.backend.catalog.CatalogDtos.NameRequest;
import com.preclinic.backend.catalog.CatalogDtos.ServiceFeeDto;
import com.preclinic.backend.catalog.CatalogDtos.ServiceFeeRequest;
import com.preclinic.backend.common.Api;

import jakarta.validation.Valid;

@RestController
@RequestMapping(Api.V1)
public class CatalogController {

	private final CatalogService catalog;

	public CatalogController(CatalogService catalog) {
		this.catalog = catalog;
	}

	/** All suggestion lists in one call. */
	@GetMapping("/catalog/bootstrap")
	public CatalogBootstrap bootstrap() {
		return catalog.bootstrap();
	}

	/** The doctor adds a symptom/diagnosis on the fly from the consultation chip pickers. */
	@PostMapping("/catalog/symptoms")
	@PreAuthorize("hasRole('DOCTOR')")
	public CatalogEntryDto addSymptom(@Valid @RequestBody NameRequest request) {
		return catalog.addSymptom(request.name());
	}

	@PostMapping("/catalog/diagnoses")
	@PreAuthorize("hasRole('DOCTOR')")
	public CatalogEntryDto addDiagnosis(@Valid @RequestBody NameRequest request) {
		return catalog.addDiagnosis(request.name());
	}

	// --- service fees (Settings -> Services & Fees; the assistant's fee picker) ---

	@GetMapping("/service-fees")
	public List<ServiceFeeDto> serviceFees() {
		return catalog.serviceFees();
	}

	@PostMapping("/service-fees")
	@PreAuthorize("hasRole('DOCTOR')")
	@ResponseStatus(HttpStatus.CREATED)
	public ServiceFeeDto addServiceFee(@Valid @RequestBody ServiceFeeRequest request) {
		return catalog.addServiceFee(request);
	}

	@PutMapping("/service-fees/{id}")
	@PreAuthorize("hasRole('DOCTOR')")
	public ServiceFeeDto updateServiceFee(@PathVariable long id, @Valid @RequestBody ServiceFeeRequest request) {
		return catalog.updateServiceFee(id, request);
	}

	@DeleteMapping("/service-fees/{id}")
	@PreAuthorize("hasRole('DOCTOR')")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void removeServiceFee(@PathVariable long id) {
		catalog.removeServiceFee(id);
	}
}
