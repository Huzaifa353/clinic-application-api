package com.preclinic.backend.medicine;

import java.util.List;

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
import com.preclinic.backend.medicine.MedicineDtos.CatalogItem;
import com.preclinic.backend.medicine.MedicineDtos.DuplicateDto;
import com.preclinic.backend.medicine.MedicineDtos.FavoriteDto;
import com.preclinic.backend.medicine.MedicineDtos.MedicineDto;
import com.preclinic.backend.medicine.MedicineDtos.MedicineRequest;
import com.preclinic.backend.medicine.MedicineDtos.MedicineStats;
import com.preclinic.backend.medicine.MedicineDtos.QuickMedicineRequest;
import com.preclinic.backend.medicine.MedicineDtos.StatusRequest;

import jakarta.validation.Valid;

/** Medicine Master. Anyone logged in can search and read; only the doctor changes the database. */
@RestController
@RequestMapping(Api.V1 + "/medicines")
public class MedicineController {

	private final MedicineService medicines;

	public MedicineController(MedicineService medicines) {
		this.medicines = medicines;
	}

	/** The prescription box: ranked, active medicines only. */
	@GetMapping("/search")
	public List<CatalogItem> search(@RequestParam String q, @RequestParam(defaultValue = "20") int limit) {
		return medicines.search(q, Math.min(Math.max(limit, 1), 50));
	}

	@GetMapping("/frequent")
	public List<CatalogItem> frequent(@RequestParam(defaultValue = "6") int limit) {
		return medicines.frequentlyUsed(Math.min(Math.max(limit, 1), 50));
	}

	@GetMapping("/recent")
	public List<CatalogItem> recent(@RequestParam(defaultValue = "6") int limit) {
		return medicines.recentlyUsed(Math.min(Math.max(limit, 1), 50));
	}

	@GetMapping("/stats")
	public MedicineStats stats() {
		return medicines.stats();
	}

	@GetMapping("/duplicates")
	public List<DuplicateDto> duplicates(@RequestParam String brandName, @RequestParam String dosageForm,
			@RequestParam List<String> ingredient) {
		return medicines.duplicates(brandName, dosageForm, ingredient);
	}

	@GetMapping("/ingredients")
	public List<String> ingredients(@RequestParam(required = false) String q) {
		return medicines.lookup("ingredients", q);
	}

	@GetMapping("/manufacturers")
	public List<String> manufacturers(@RequestParam(required = false) String q) {
		return medicines.lookup("manufacturers", q);
	}

	@GetMapping("/brands")
	public List<String> brands(@RequestParam(required = false) String q) {
		return medicines.lookup("brands", q);
	}

	/** The Medicine Library table: search, filters, sort and paging on the server. */
	@GetMapping
	public PageResult<MedicineDto> list(
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String form,
			@RequestParam(required = false) Long manufacturerId,
			@RequestParam(required = false) String sort,
			@RequestParam(required = false) String dir,
			@RequestParam(defaultValue = "0") int skip,
			@RequestParam(defaultValue = "10") int limit) {
		return medicines.list(new MedicineService.Query(q, status, form, manufacturerId, sort, dir, Math.max(skip, 0),
				Math.min(Math.max(limit, 1), 100)));
	}

	@GetMapping("/{id}")
	public MedicineDto get(@PathVariable long id) {
		return medicines.get(id);
	}

	@GetMapping("/{id}/alternatives")
	public List<MedicineDto> alternatives(@PathVariable long id) {
		return medicines.alternatives(id);
	}

	/** Refuses a likely duplicate (409 POSSIBLE_DUPLICATE) unless {@code force=true}. */
	@PostMapping
	@PreAuthorize("hasRole('DOCTOR')")
	@ResponseStatus(HttpStatus.CREATED)
	public MedicineDto create(@Valid @RequestBody MedicineRequest request,
			@RequestParam(defaultValue = "false") boolean force) {
		return medicines.create(request, force);
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasRole('DOCTOR')")
	public MedicineDto update(@PathVariable long id, @Valid @RequestBody MedicineRequest request) {
		return medicines.update(id, request);
	}

	@PatchMapping("/{id}/status")
	@PreAuthorize("hasRole('DOCTOR')")
	public MedicineDto status(@PathVariable long id, @Valid @RequestBody StatusRequest request) {
		return medicines.setStatus(id, request.status());
	}

	@PostMapping("/{id}/favorite")
	@PreAuthorize("hasRole('DOCTOR')")
	public FavoriteDto favorite(@PathVariable long id) {
		return new FavoriteDto(medicines.toggleFavorite(id));
	}

	/** "Type it and go": a minimal unverified record so prescribing never stops to fill in master data. */
	@PostMapping("/quick")
	@PreAuthorize("hasRole('DOCTOR')")
	public CatalogItem quick(@Valid @RequestBody QuickMedicineRequest request) {
		return medicines.quickAdd(request.name());
	}
}
