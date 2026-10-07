package com.preclinic.backend.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class CatalogDtos {

	private CatalogDtos() {
	}

	/** {@code id} is the stable code the frontend stores on an order, e.g. "cbc". */
	public record InvestigationDto(String id, String name, String fullName, String group) {
	}

	public record FrequencyDto(String code, String meaning) {
	}

	/**
	 * Every suggestion list the consultation room needs, in one call (the frontend reads them
	 * synchronously while the doctor types).
	 */
	public record CatalogBootstrap(List<String> symptoms, List<String> diagnoses, List<InvestigationDto> investigations,
			List<String> examinationCategories, Map<String, List<String>> examinationFindings,
			List<FrequencyDto> frequencyCodes, List<String> durationOptions, List<String> timingOptions) {
	}

	public record NameRequest(@NotBlank @Size(max = 200) String name) {
	}

	/** The canonical name (an existing entry wins over the typed spelling) and whether it was new. */
	public record CatalogEntryDto(String name, boolean created) {
	}

	public record ServiceFeeDto(long id, String name, BigDecimal fee) {
	}

	public record ServiceFeeRequest(@NotBlank @Size(max = 200) String name,
			@NotNull @DecimalMin("0") @DecimalMax("100000000") BigDecimal fee) {
	}
}
