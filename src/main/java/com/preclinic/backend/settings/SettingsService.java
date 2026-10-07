package com.preclinic.backend.settings;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.preclinic.backend.common.SecretCipher;
import com.preclinic.backend.file.FileService;
import com.preclinic.backend.security.CurrentUser;
import com.preclinic.backend.settings.SettingsDtos.EasyPaisaDto;
import com.preclinic.backend.settings.SettingsDtos.IntegrationsDto;
import com.preclinic.backend.settings.SettingsDtos.JazzCashDto;
import com.preclinic.backend.settings.SettingsDtos.LocalizationDto;
import com.preclinic.backend.settings.SettingsDtos.PrintSettingsDto;
import com.preclinic.backend.settings.SettingsDtos.ThemeDto;
import com.preclinic.backend.settings.SettingsDtos.UpdateIntegrationsRequest;
import com.preclinic.backend.settings.SettingsDtos.UpdateLocalizationRequest;
import com.preclinic.backend.settings.SettingsDtos.UpdatePrintSettingsRequest;
import com.preclinic.backend.settings.SettingsDtos.UpdateThemeRequest;
import com.preclinic.backend.settings.SettingsDtos.WhatsappDto;

/** Per-clinic settings: printing & documents, localization, theme, third-party integrations. */
@Service
public class SettingsService {

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final FileService files;
	private final SecretCipher cipher;

	public SettingsService(JdbcClient jdbc, CurrentUser currentUser, FileService files, SecretCipher cipher) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.files = files;
		this.cipher = cipher;
	}

	/** Settings rows are created lazily so a brand-new clinic works without a manual step. */
	private long ensureRows() {
		long clinicId = currentUser.clinicId();
		jdbc.sql("""
				insert into clinic_settings (clinic_id, print_clinic_name)
				select id, name from clinic where id = :c
				on conflict (clinic_id) do nothing
				""").param("c", clinicId).update();
		jdbc.sql("insert into integration_settings (clinic_id) values (:c) on conflict (clinic_id) do nothing")
				.param("c", clinicId).update();
		return clinicId;
	}

	// --- Printing & documents -------------------------------------------------

	@Transactional
	public PrintSettingsDto getPrint() {
		return readPrint(ensureRows());
	}

	private PrintSettingsDto readPrint(long clinicId) {
		return jdbc.sql("""
				select print_clinic_name as clinic_name, print_address as clinic_address, print_phone as clinic_phone,
				       print_whatsapp as clinic_whatsapp, print_email as clinic_email, print_website as clinic_website,
				       logo_file_id, signature_file_id, show_signature, header_layout, footer_text,
				       footer_show_disclaimer, default_language
				from clinic_settings where clinic_id = :c
				""").param("c", clinicId).query(PrintSettingsDto.class).single();
	}

	@Transactional
	public PrintSettingsDto updatePrint(UpdatePrintSettingsRequest r) {
		long clinicId = ensureRows();
		jdbc.sql("""
				update clinic_settings set print_clinic_name = :name, print_address = :address, print_phone = :phone,
				       print_whatsapp = :whatsapp, print_email = :email, print_website = :website,
				       show_signature = :showSignature, header_layout = :layout, footer_text = :footer,
				       footer_show_disclaimer = :disclaimer, default_language = :language, updated_at = now()
				where clinic_id = :c
				""")
				.param("name", r.clinicName().trim())
				.param("address", blankToNull(r.clinicAddress()))
				.param("phone", blankToNull(r.clinicPhone()))
				.param("whatsapp", blankToNull(r.clinicWhatsapp()))
				.param("email", blankToNull(r.clinicEmail()))
				.param("website", blankToNull(r.clinicWebsite()))
				.param("showSignature", r.showSignature())
				.param("layout", r.headerLayout())
				.param("footer", blankToNull(r.footerText()))
				.param("disclaimer", r.footerShowDisclaimer())
				.param("language", r.defaultLanguage())
				.param("c", clinicId).update();
		return readPrint(clinicId);
	}

	@Transactional
	public PrintSettingsDto setPrintImage(ImageSlot slot, MultipartFile upload) {
		long clinicId = replaceImage(slot, upload);
		return readPrint(clinicId);
	}

	@Transactional
	public PrintSettingsDto clearPrintImage(ImageSlot slot) {
		long clinicId = removeImage(slot);
		return readPrint(clinicId);
	}

	// --- Localization -----------------------------------------------------------

	@Transactional
	public LocalizationDto getLocalization() {
		return readLocalization(ensureRows());
	}

	private LocalizationDto readLocalization(long clinicId) {
		return jdbc.sql("""
				select c.timezone as time_zone, s.date_format, s.time_format, s.currency_symbol
				from clinic c join clinic_settings s on s.clinic_id = c.id where c.id = :c
				""").param("c", clinicId).query(LocalizationDto.class).single();
	}

	@Transactional
	public LocalizationDto updateLocalization(UpdateLocalizationRequest r) {
		long clinicId = ensureRows();
		jdbc.sql("""
				update clinic_settings set date_format = :date, time_format = :time, currency_symbol = :currency,
				       updated_at = now() where clinic_id = :c
				""")
				.param("date", r.dateFormat().trim()).param("time", r.timeFormat())
				.param("currency", r.currencySymbol().trim()).param("c", clinicId).update();
		return readLocalization(clinicId);
	}

	// --- Theme --------------------------------------------------------------------

	@Transactional
	public ThemeDto getTheme() {
		return readTheme(ensureRows());
	}

	private ThemeDto readTheme(long clinicId) {
		return jdbc.sql("""
				select website_name, theme_logo_file_id as logo_file_id, theme_light_logo_file_id as light_logo_file_id,
				       theme_favicon_file_id as favicon_file_id
				from clinic_settings where clinic_id = :c
				""").param("c", clinicId).query(ThemeDto.class).single();
	}

	@Transactional
	public ThemeDto updateTheme(UpdateThemeRequest r) {
		long clinicId = ensureRows();
		jdbc.sql("update clinic_settings set website_name = :name, updated_at = now() where clinic_id = :c")
				.param("name", r.websiteName().trim()).param("c", clinicId).update();
		return readTheme(clinicId);
	}

	@Transactional
	public ThemeDto setThemeImage(ImageSlot slot, MultipartFile upload) {
		return readTheme(replaceImage(slot, upload));
	}

	@Transactional
	public ThemeDto clearThemeImage(ImageSlot slot) {
		return readTheme(removeImage(slot));
	}

	// --- Integrations -------------------------------------------------------------

	@Transactional
	public IntegrationsDto getIntegrations() {
		return readIntegrations(ensureRows());
	}

	private IntegrationsDto readIntegrations(long clinicId) {
		return jdbc.sql("select * from integration_settings where clinic_id = :c").param("c", clinicId)
				.query((rs, i) -> new IntegrationsDto(
						new WhatsappDto(rs.getBoolean("whatsapp_enabled"), rs.getString("whatsapp_phone_number_id"),
								rs.getString("whatsapp_business_account_id"),
								rs.getString("whatsapp_access_token_enc") != null,
								rs.getString("whatsapp_webhook_token_enc") != null),
						new JazzCashDto(rs.getBoolean("jazzcash_enabled"), rs.getString("jazzcash_merchant_id"),
								rs.getString("jazzcash_password_enc") != null,
								rs.getString("jazzcash_integrity_salt_enc") != null),
						new EasyPaisaDto(rs.getBoolean("easypaisa_enabled"), rs.getString("easypaisa_store_id"),
								rs.getString("easypaisa_account_number"), rs.getString("easypaisa_api_key_enc") != null)))
				.single();
	}

	@Transactional
	public IntegrationsDto updateIntegrations(UpdateIntegrationsRequest r) {
		long clinicId = ensureRows();
		var current = jdbc.sql("select * from integration_settings where clinic_id = :c").param("c", clinicId)
				.query((rs, i) -> new String[] { rs.getString("whatsapp_access_token_enc"),
						rs.getString("whatsapp_webhook_token_enc"), rs.getString("jazzcash_password_enc"),
						rs.getString("jazzcash_integrity_salt_enc"), rs.getString("easypaisa_api_key_enc") })
				.single();
		jdbc.sql("""
				update integration_settings set
				  whatsapp_enabled = coalesce(:waEnabled, whatsapp_enabled),
				  whatsapp_phone_number_id = case when :waPhoneSet then :waPhone else whatsapp_phone_number_id end,
				  whatsapp_business_account_id = case when :waAccountSet then :waAccount else whatsapp_business_account_id end,
				  whatsapp_access_token_enc = :waToken,
				  whatsapp_webhook_token_enc = :waWebhook,
				  jazzcash_enabled = coalesce(:jcEnabled, jazzcash_enabled),
				  jazzcash_merchant_id = case when :jcMerchantSet then :jcMerchant else jazzcash_merchant_id end,
				  jazzcash_password_enc = :jcPassword,
				  jazzcash_integrity_salt_enc = :jcSalt,
				  easypaisa_enabled = coalesce(:epEnabled, easypaisa_enabled),
				  easypaisa_store_id = case when :epStoreSet then :epStore else easypaisa_store_id end,
				  easypaisa_account_number = case when :epAccountSet then :epAccount else easypaisa_account_number end,
				  easypaisa_api_key_enc = :epKey,
				  updated_at = now()
				where clinic_id = :c
				""")
				.param("waEnabled", r.whatsappEnabled())
				.param("waPhoneSet", r.whatsappPhoneNumberId() != null)
				.param("waPhone", blankToNull(r.whatsappPhoneNumberId()))
				.param("waAccountSet", r.whatsappBusinessAccountId() != null)
				.param("waAccount", blankToNull(r.whatsappBusinessAccountId()))
				.param("waToken", secret(r.whatsappAccessToken(), current[0]))
				.param("waWebhook", secret(r.whatsappWebhookVerifyToken(), current[1]))
				.param("jcEnabled", r.jazzCashEnabled())
				.param("jcMerchantSet", r.jazzCashMerchantId() != null)
				.param("jcMerchant", blankToNull(r.jazzCashMerchantId()))
				.param("jcPassword", secret(r.jazzCashPassword(), current[2]))
				.param("jcSalt", secret(r.jazzCashIntegritySalt(), current[3]))
				.param("epEnabled", r.easyPaisaEnabled())
				.param("epStoreSet", r.easyPaisaStoreId() != null)
				.param("epStore", blankToNull(r.easyPaisaStoreId()))
				.param("epAccountSet", r.easyPaisaAccountNumber() != null)
				.param("epAccount", blankToNull(r.easyPaisaAccountNumber()))
				.param("epKey", secret(r.easyPaisaApiKey(), current[4]))
				.param("c", clinicId).update();
		return readIntegrations(clinicId);
	}

	/** null = keep what is stored, blank = clear, otherwise encrypt the new value. */
	private String secret(String incoming, String existing) {
		if (incoming == null) {
			return existing;
		}
		return incoming.isBlank() ? null : cipher.encrypt(incoming.trim());
	}

	// --- shared image handling -----------------------------------------------------

	private long replaceImage(ImageSlot slot, MultipartFile upload) {
		long clinicId = ensureRows();
		Long old = currentImageId(slot, clinicId);
		long newId = files.store(upload, slot.allowedTypes, FileService.MAX_IMAGE_BYTES);
		jdbc.sql("update clinic_settings set " + slot.column + " = :id, updated_at = now() where clinic_id = :c")
				.param("id", newId).param("c", clinicId).update();
		files.delete(old);
		return clinicId;
	}

	private long removeImage(ImageSlot slot) {
		long clinicId = ensureRows();
		Long old = currentImageId(slot, clinicId);
		jdbc.sql("update clinic_settings set " + slot.column + " = null, updated_at = now() where clinic_id = :c")
				.param("c", clinicId).update();
		files.delete(old);
		return clinicId;
	}

	/** The file currently in the slot, or null. (A row mapper may not return null, hence the sentinel.) */
	private Long currentImageId(ImageSlot slot, long clinicId) {
		long id = jdbc.sql("select " + slot.column + " from clinic_settings where clinic_id = :c")
				.param("c", clinicId)
				.query((rs, i) -> {
					long value = rs.getLong(1);
					return rs.wasNull() ? -1L : value;
				}).single();
		return id < 0 ? null : id;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
