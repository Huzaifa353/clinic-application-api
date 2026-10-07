package com.preclinic.backend.settings;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class SettingsDtos {

	private SettingsDtos() {
	}

	// --- Printing & documents -------------------------------------------------

	/** Images are served by {@code GET /files/{id}}; ids are null when nothing is uploaded. */
	public record PrintSettingsDto(String clinicName, String clinicAddress, String clinicPhone, String clinicWhatsapp,
			String clinicEmail, String clinicWebsite, Long logoFileId, Long signatureFileId, boolean showSignature,
			String headerLayout, String footerText, boolean footerShowDisclaimer, String defaultLanguage) {
	}

	/** Full replacement of the text/flag fields; images change through the upload endpoints. */
	public record UpdatePrintSettingsRequest(
			@NotBlank @Size(max = 200) String clinicName,
			@Size(max = 500) String clinicAddress,
			@Size(max = 100) String clinicPhone,
			@Size(max = 100) String clinicWhatsapp,
			@Size(max = 200) String clinicEmail,
			@Size(max = 200) String clinicWebsite,
			@NotNull Boolean showSignature,
			@NotNull @Pattern(regexp = "classic|doctor-focused|clinic-focused") String headerLayout,
			@Size(max = 1000) String footerText,
			@NotNull Boolean footerShowDisclaimer,
			@NotNull @Pattern(regexp = "en|ur|bilingual") String defaultLanguage) {
	}

	// --- Localization -----------------------------------------------------------

	/** {@code timeZone} is an IANA id; it is read-only here (set through server configuration). */
	public record LocalizationDto(String timeZone, String dateFormat, String timeFormat, String currencySymbol) {
	}

	public record UpdateLocalizationRequest(
			// a display sample the clinic picked ("DD-MM-YYYY", "15 May 2026", "15/05/2026" ...), stored as given
			@NotBlank @Size(max = 30) String dateFormat,
			@NotNull @Pattern(regexp = "12 Hours|24 Hours") String timeFormat,
			@NotBlank @Size(max = 10) String currencySymbol) {
	}

	// --- Theme --------------------------------------------------------------------

	public record ThemeDto(String websiteName, Long logoFileId, Long lightLogoFileId, Long faviconFileId) {
	}

	public record UpdateThemeRequest(@NotBlank @Size(max = 100) String websiteName) {
	}

	// --- Integrations (secrets are write-only: the API only says whether one is set) -------------

	public record WhatsappDto(boolean enabled, String phoneNumberId, String businessAccountId, boolean accessTokenSet,
			boolean webhookVerifyTokenSet) {
	}

	public record JazzCashDto(boolean enabled, String merchantId, boolean passwordSet, boolean integritySaltSet) {
	}

	public record EasyPaisaDto(boolean enabled, String storeId, String accountNumber, boolean apiKeySet) {
	}

	public record IntegrationsDto(WhatsappDto whatsapp, JazzCashDto jazzCash, EasyPaisaDto easyPaisa) {
	}

	/**
	 * Secret fields: omitted or null keeps the stored value, an empty string clears it, anything else
	 * replaces it.
	 */
	public record UpdateIntegrationsRequest(
			Boolean whatsappEnabled,
			@Size(max = 200) String whatsappPhoneNumberId,
			@Size(max = 200) String whatsappBusinessAccountId,
			@Size(max = 1000) String whatsappAccessToken,
			@Size(max = 500) String whatsappWebhookVerifyToken,
			Boolean jazzCashEnabled,
			@Size(max = 200) String jazzCashMerchantId,
			@Size(max = 500) String jazzCashPassword,
			@Size(max = 500) String jazzCashIntegritySalt,
			Boolean easyPaisaEnabled,
			@Size(max = 200) String easyPaisaStoreId,
			@Size(max = 100) String easyPaisaAccountNumber,
			@Size(max = 500) String easyPaisaApiKey) {
	}

	// --- Translations -------------------------------------------------------------

	public record TranslationDto(String category, String sourceText, String urduText) {
	}

	public record UpsertTranslationRequest(
			@NotNull @Pattern(regexp = "symptom|diagnosis|investigation|examination|frequency|duration|instruction|medicine") String category,
			@NotBlank @Size(max = 300) String sourceText,
			@NotBlank @Size(max = 1000) String urduText) {
	}
}
