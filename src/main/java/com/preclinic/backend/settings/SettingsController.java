package com.preclinic.backend.settings;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.preclinic.backend.common.Api;
import com.preclinic.backend.settings.SettingsDtos.IntegrationsDto;
import com.preclinic.backend.settings.SettingsDtos.LocalizationDto;
import com.preclinic.backend.settings.SettingsDtos.PrintSettingsDto;
import com.preclinic.backend.settings.SettingsDtos.ThemeDto;
import com.preclinic.backend.settings.SettingsDtos.UpdateIntegrationsRequest;
import com.preclinic.backend.settings.SettingsDtos.UpdateLocalizationRequest;
import com.preclinic.backend.settings.SettingsDtos.UpdatePrintSettingsRequest;
import com.preclinic.backend.settings.SettingsDtos.UpdateThemeRequest;

import jakarta.validation.Valid;

/**
 * Settings screens. Everyone can read print/localization/theme (the assistant prints receipts);
 * only the doctor changes anything, and only the doctor sees the integrations page.
 */
@RestController
@RequestMapping(Api.V1 + "/settings")
public class SettingsController {

	private final SettingsService settings;

	public SettingsController(SettingsService settings) {
		this.settings = settings;
	}

	// --- print ---

	@GetMapping("/print")
	public PrintSettingsDto getPrint() {
		return settings.getPrint();
	}

	@PutMapping("/print")
	@PreAuthorize("hasRole('DOCTOR')")
	public PrintSettingsDto updatePrint(@Valid @RequestBody UpdatePrintSettingsRequest request) {
		return settings.updatePrint(request);
	}

	@PostMapping(path = "/print/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("hasRole('DOCTOR')")
	public PrintSettingsDto uploadLogo(@RequestParam("file") MultipartFile file) {
		return settings.setPrintImage(ImageSlot.PRINT_LOGO, file);
	}

	@DeleteMapping("/print/logo")
	@PreAuthorize("hasRole('DOCTOR')")
	public PrintSettingsDto removeLogo() {
		return settings.clearPrintImage(ImageSlot.PRINT_LOGO);
	}

	@PostMapping(path = "/print/signature", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("hasRole('DOCTOR')")
	public PrintSettingsDto uploadSignature(@RequestParam("file") MultipartFile file) {
		return settings.setPrintImage(ImageSlot.PRINT_SIGNATURE, file);
	}

	@DeleteMapping("/print/signature")
	@PreAuthorize("hasRole('DOCTOR')")
	public PrintSettingsDto removeSignature() {
		return settings.clearPrintImage(ImageSlot.PRINT_SIGNATURE);
	}

	// --- localization ---

	@GetMapping("/localization")
	public LocalizationDto getLocalization() {
		return settings.getLocalization();
	}

	@PutMapping("/localization")
	@PreAuthorize("hasRole('DOCTOR')")
	public LocalizationDto updateLocalization(@Valid @RequestBody UpdateLocalizationRequest request) {
		return settings.updateLocalization(request);
	}

	// --- theme ---

	@GetMapping("/theme")
	public ThemeDto getTheme() {
		return settings.getTheme();
	}

	@PutMapping("/theme")
	@PreAuthorize("hasRole('DOCTOR')")
	public ThemeDto updateTheme(@Valid @RequestBody UpdateThemeRequest request) {
		return settings.updateTheme(request);
	}

	@PostMapping(path = "/theme/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("hasRole('DOCTOR')")
	public ThemeDto uploadThemeLogo(@RequestParam("file") MultipartFile file) {
		return settings.setThemeImage(ImageSlot.THEME_LOGO, file);
	}

	@DeleteMapping("/theme/logo")
	@PreAuthorize("hasRole('DOCTOR')")
	public ThemeDto removeThemeLogo() {
		return settings.clearThemeImage(ImageSlot.THEME_LOGO);
	}

	@PostMapping(path = "/theme/light-logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("hasRole('DOCTOR')")
	public ThemeDto uploadThemeLightLogo(@RequestParam("file") MultipartFile file) {
		return settings.setThemeImage(ImageSlot.THEME_LIGHT_LOGO, file);
	}

	@DeleteMapping("/theme/light-logo")
	@PreAuthorize("hasRole('DOCTOR')")
	public ThemeDto removeThemeLightLogo() {
		return settings.clearThemeImage(ImageSlot.THEME_LIGHT_LOGO);
	}

	@PostMapping(path = "/theme/favicon", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("hasRole('DOCTOR')")
	public ThemeDto uploadThemeFavicon(@RequestParam("file") MultipartFile file) {
		return settings.setThemeImage(ImageSlot.THEME_FAVICON, file);
	}

	@DeleteMapping("/theme/favicon")
	@PreAuthorize("hasRole('DOCTOR')")
	public ThemeDto removeThemeFavicon() {
		return settings.clearThemeImage(ImageSlot.THEME_FAVICON);
	}

	// --- integrations ---

	@GetMapping("/integrations")
	@PreAuthorize("hasRole('DOCTOR')")
	public IntegrationsDto getIntegrations() {
		return settings.getIntegrations();
	}

	@PutMapping("/integrations")
	@PreAuthorize("hasRole('DOCTOR')")
	public IntegrationsDto updateIntegrations(@Valid @RequestBody UpdateIntegrationsRequest request) {
		return settings.updateIntegrations(request);
	}
}
