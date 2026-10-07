package com.preclinic.backend.medicine;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class MedicineDtos {

	/** The values the product form offers; also what the database stores. */
	public static final String DOSAGE_FORMS = "Tablet|Capsule|Syrup|Suspension|Solution|Drops|Injection|Infusion|Cream|Ointment"
			+ "|Gel|Lotion|Inhaler|Nebulizer Solution|Suppository|Powder|Sachet|Spray|Eye Drops|Ear Drops|Nasal Drops";
	public static final String ROUTES = "Oral|IV|IM|SC|Topical|Ophthalmic|Otic|Nasal|Inhalation|Rectal|Vaginal";
	public static final String STATUSES = "Active|Inactive|Discontinued|Under Review|Unverified|Archived";

	private MedicineDtos() {
	}

	/** What the prescription box and prescription lists need about a medicine. */
	public record CatalogItem(long productId, String name, String genericName, String strengthLabel, String dosageForm,
			String manufacturerName, boolean isFavorite, int usageCount, Instant lastUsedAt) {
	}

	public record IngredientLine(long ingredientId, String name, BigDecimal strengthValue, String strengthUnit,
			BigDecimal strengthPerValue, String strengthPerUnit) {
	}

	/** {@code latestPrice} is the newest entry of the pack's append-only price history. */
	public record PackDto(long id, BigDecimal quantity, String unit, String packDescription, String status,
			BigDecimal latestPrice, String currency, LocalDate priceEffectiveFrom) {
	}

	public record MedicineDto(long id, String productName, long brandId, String brandName, long manufacturerId,
			String manufacturerName, String dosageForm, String route, String status, String verificationStatus,
			String category, String registrationNumber, String registrationStatus, List<String> aliases,
			String genericName, String strengthLabel, String compositionLabel, List<IngredientLine> ingredients,
			List<PackDto> packs, boolean isFavorite, int usageCount, Instant lastUsedAt, boolean clinicOwned,
			Instant createdAt, Instant updatedAt) {
	}

	public record MedicineStats(long total, long active, long brands, long generics) {
	}

	public record IngredientInput(
			@NotBlank @Size(max = 200) String name,
			@NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000000") BigDecimal strengthValue,
			@NotBlank @Size(max = 20) String strengthUnit,
			@DecimalMin(value = "0", inclusive = false) @DecimalMax("1000000") BigDecimal strengthPerValue,
			@Size(max = 20) String strengthPerUnit) {
	}

	/** A pack with an {@code id} updates that pack; without one it is a new pack. */
	public record PackInput(
			Long id,
			@NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000000") BigDecimal quantity,
			@NotBlank @Size(max = 30) String unit,
			@NotBlank @Size(max = 100) String packDescription,
			@DecimalMin("0") @DecimalMax("100000000") BigDecimal price) {
	}

	public record MedicineRequest(
			@NotBlank @Size(max = 200) String productName,
			@NotBlank @Size(max = 200) String brandName,
			@NotBlank @Size(max = 200) String manufacturerName,
			@NotNull @Pattern(regexp = DOSAGE_FORMS) String dosageForm,
			@NotNull @Pattern(regexp = ROUTES) String route,
			@NotNull @Pattern(regexp = STATUSES) String status,
			@Size(max = 100) String registrationNumber,
			@Size(max = 100) String registrationStatus,
			@Size(max = 100) String category,
			@NotEmpty @Size(max = 10) List<@Valid IngredientInput> ingredients,
			@Size(max = 20) List<@Valid PackInput> packs) {
	}

	public record StatusRequest(@NotNull @Pattern(regexp = STATUSES) String status) {
	}

	public record QuickMedicineRequest(@NotBlank @Size(max = 200) String name) {
	}

	public record FavoriteDto(boolean isFavorite) {
	}

	public record DuplicateDto(MedicineDto product, String reason) {
	}
}
