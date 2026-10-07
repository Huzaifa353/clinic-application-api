package com.preclinic.backend.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import com.jayway.jsonpath.JsonPath;
import com.preclinic.backend.ApiIntegrationTest;
import com.preclinic.backend.common.SecretCipher;
import com.preclinic.backend.user.AppUser;
import com.preclinic.backend.user.Clinic;
import com.preclinic.backend.user.Role;

class SettingsApiTest extends ApiIntegrationTest {

	static final byte[] PNG = Base64.getDecoder().decode(
			"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

	@Autowired
	SecretCipher cipher;

	AppUser doctor;
	AppUser assistant;

	@BeforeEach
	void createUsers() {
		doctor = newUser("doc@test.local", "Dr. Test", Role.DOCTOR);
		assistant = newUser("asst@test.local", "Ayesha Assistant", Role.ASSISTANT);
	}

	private MockMultipartFile image(String name, String type, byte[] bytes) {
		return new MockMultipartFile("file", name, type, bytes);
	}

	private long uploadLogo(byte[] bytes) throws Exception {
		String body = mvc.perform(multipart("/api/v1/settings/print/logo").file(image("logo.png", "image/png", bytes))
				.header(HttpHeaders.AUTHORIZATION, bearer(doctor)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.logoFileId")).longValue();
	}

	// --- print settings ---

	@Test
	void printSettingsHaveSensibleDefaults() throws Exception {
		mvc.perform(get("/api/v1/settings/print").header(HttpHeaders.AUTHORIZATION, bearer(assistant)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.clinicName").value("Clinstra Family Clinic"))
				.andExpect(jsonPath("$.headerLayout").value("classic"))
				.andExpect(jsonPath("$.defaultLanguage").value("en"))
				.andExpect(jsonPath("$.showSignature").value(false))
				.andExpect(jsonPath("$.logoFileId").doesNotExist());
	}

	@Test
	void aDoctorSavesPrintSettingsAndEveryoneReadsThemBack() throws Exception {
		String body = """
				{"clinicName":"Sara Family Clinic","clinicAddress":"F-10, Islamabad","clinicPhone":"051-1234567",
				 "clinicWhatsapp":"0300-1234567","clinicEmail":"care@sara.pk","clinicWebsite":"",
				 "showSignature":true,"headerLayout":"doctor-focused","footerText":"For appointments call 0300-1234567",
				 "footerShowDisclaimer":true,"defaultLanguage":"bilingual"}""";
		mvc.perform(put("/api/v1/settings/print").header(HttpHeaders.AUTHORIZATION, bearer(doctor))
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.clinicName").value("Sara Family Clinic"));
		mvc.perform(get("/api/v1/settings/print").header(HttpHeaders.AUTHORIZATION, bearer(assistant)))
				.andExpect(jsonPath("$.clinicAddress").value("F-10, Islamabad"))
				.andExpect(jsonPath("$.clinicWebsite").doesNotExist())
				.andExpect(jsonPath("$.showSignature").value(true))
				.andExpect(jsonPath("$.headerLayout").value("doctor-focused"))
				.andExpect(jsonPath("$.defaultLanguage").value("bilingual"));
	}

	@Test
	void printSettingsValidateTheirChoices() throws Exception {
		String body = """
				{"clinicName":"X","showSignature":false,"headerLayout":"fancy","footerShowDisclaimer":false,
				 "defaultLanguage":"en"}""";
		mvc.perform(put("/api/v1/settings/print").header(HttpHeaders.AUTHORIZATION, bearer(doctor))
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("headerLayout"));
	}

	@Test
	void anAssistantCannotChangePrintSettings() throws Exception {
		String body = """
				{"clinicName":"X","showSignature":false,"headerLayout":"classic","footerShowDisclaimer":false,
				 "defaultLanguage":"en"}""";
		mvc.perform(put("/api/v1/settings/print").header(HttpHeaders.AUTHORIZATION, bearer(assistant))
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isForbidden());
	}

	// --- logo / signature uploads ---

	@Test
	void aLogoCanBeUploadedServedReplacedAndRemoved() throws Exception {
		long first = uploadLogo(PNG);

		mvc.perform(get("/api/v1/files/" + first).header(HttpHeaders.AUTHORIZATION, bearer(assistant)))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type", "image/png"))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(content().bytes(PNG));

		long second = uploadLogo(PNG);
		assertThat(second).isNotEqualTo(first);
		mvc.perform(get("/api/v1/files/" + first).header(HttpHeaders.AUTHORIZATION, bearer(doctor)))
				.andExpect(status().isNotFound());

		mvc.perform(delete("/api/v1/settings/print/logo").header(HttpHeaders.AUTHORIZATION, bearer(doctor)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.logoFileId").doesNotExist());
		mvc.perform(get("/api/v1/files/" + second).header(HttpHeaders.AUTHORIZATION, bearer(doctor)))
				.andExpect(status().isNotFound());
	}

	@Test
	void theSignatureIsStoredSeparatelyFromTheLogo() throws Exception {
		uploadLogo(PNG);
		mvc.perform(multipart("/api/v1/settings/print/signature").file(image("sig.png", "image/png", PNG))
				.header(HttpHeaders.AUTHORIZATION, bearer(doctor)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.logoFileId").exists())
				.andExpect(jsonPath("$.signatureFileId").exists());
	}

	@Test
	void anUploadIsRecognisedByItsBytesNotItsName() throws Exception {
		mvc.perform(multipart("/api/v1/settings/print/logo")
				.file(image("logo.png", "image/png", "this is just text, not a picture".getBytes()))
				.header(HttpHeaders.AUTHORIZATION, bearer(doctor)))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"));
		byte[] pdf = "%PDF-1.4 fake".getBytes();
		mvc.perform(multipart("/api/v1/settings/print/logo").file(image("logo.png", "image/png", pdf))
				.header(HttpHeaders.AUTHORIZATION, bearer(doctor)))
				.andExpect(status().isUnprocessableEntity());
	}

	@Test
	void anOversizedLogoIsRefused() throws Exception {
		byte[] big = new byte[2 * 1024 * 1024 + 1];
		System.arraycopy(PNG, 0, big, 0, PNG.length);
		mvc.perform(multipart("/api/v1/settings/print/logo").file(image("logo.png", "image/png", big))
				.header(HttpHeaders.AUTHORIZATION, bearer(doctor)))
				.andExpect(status().isPayloadTooLarge())
				.andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
	}

	@Test
	void anEmptyUploadIsRefused() throws Exception {
		mvc.perform(multipart("/api/v1/settings/print/logo").file(image("logo.png", "image/png", new byte[0]))
				.header(HttpHeaders.AUTHORIZATION, bearer(doctor)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("FILE_REQUIRED"));
	}

	@Test
	void anAssistantCannotUploadALogo() throws Exception {
		mvc.perform(multipart("/api/v1/settings/print/logo").file(image("logo.png", "image/png", PNG))
				.header(HttpHeaders.AUTHORIZATION, bearer(assistant)))
				.andExpect(status().isForbidden());
	}

	@Test
	void anotherClinicCannotFetchOurFile() throws Exception {
		long id = uploadLogo(PNG);
		Clinic other = otherClinic();
		AppUser stranger = newUser(other.getId(), "stranger@other.local", "Stranger", Role.DOCTOR);
		mvc.perform(get("/api/v1/files/" + id).header(HttpHeaders.AUTHORIZATION, bearer(stranger)))
				.andExpect(status().isNotFound());
	}

	// --- localization / theme ---

	@Test
	void localizationDefaultsAndUpdate() throws Exception {
		mvc.perform(get("/api/v1/settings/localization").header(HttpHeaders.AUTHORIZATION, bearer(assistant)))
				.andExpect(jsonPath("$.timeZone").value("Asia/Karachi"))
				.andExpect(jsonPath("$.dateFormat").value("DD-MM-YYYY"))
				.andExpect(jsonPath("$.timeFormat").value("24 Hours"))
				.andExpect(jsonPath("$.currencySymbol").value("Rs."));
		mvc.perform(put("/api/v1/settings/localization").header(HttpHeaders.AUTHORIZATION, bearer(doctor))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"dateFormat\":\"YYYY-MM-DD\",\"timeFormat\":\"12 Hours\",\"currencySymbol\":\"PKR\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.timeFormat").value("12 Hours"))
				.andExpect(jsonPath("$.currencySymbol").value("PKR"));
		mvc.perform(put("/api/v1/settings/localization").header(HttpHeaders.AUTHORIZATION, bearer(doctor))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"dateFormat\":\"DD-MM-YYYY\",\"timeFormat\":\"25 Hours\",\"currencySymbol\":\"Rs.\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void themeNameAndFaviconCanBeSet() throws Exception {
		mvc.perform(put("/api/v1/settings/theme").header(HttpHeaders.AUTHORIZATION, bearer(doctor))
				.contentType(MediaType.APPLICATION_JSON).content("{\"websiteName\":\"Sara Clinic\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.websiteName").value("Sara Clinic"));
		byte[] ico = { 0, 0, 1, 0, 1, 0, 16, 16, 0, 0, 1, 0, 32, 0 };
		mvc.perform(multipart("/api/v1/settings/theme/favicon").file(image("favicon.ico", "image/x-icon", ico))
				.header(HttpHeaders.AUTHORIZATION, bearer(doctor)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.faviconFileId").exists());
		mvc.perform(multipart("/api/v1/settings/theme/logo").file(image("favicon.ico", "image/x-icon", ico))
				.header(HttpHeaders.AUTHORIZATION, bearer(doctor)))
				.andExpect(status().isUnprocessableEntity());
	}

	// --- integrations ---

	@Test
	void integrationSecretsAreEncryptedAtRestAndNeverReturned() throws Exception {
		mvc.perform(get("/api/v1/settings/integrations").header(HttpHeaders.AUTHORIZATION, bearer(doctor)))
				.andExpect(jsonPath("$.whatsapp.enabled").value(false))
				.andExpect(jsonPath("$.whatsapp.accessTokenSet").value(false));

		String secret = "EAAG-super-secret-token-123";
		mvc.perform(put("/api/v1/settings/integrations").header(HttpHeaders.AUTHORIZATION, bearer(doctor))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"whatsappEnabled\":true,\"whatsappPhoneNumberId\":\"1055\",\"whatsappAccessToken\":\""
						+ secret + "\",\"jazzCashMerchantId\":\"MC123\",\"jazzCashPassword\":\"pw-secret\"}"))
				.andExpect(status().isOk())
				.andExpect(content().string(not(containsString(secret))))
				.andExpect(content().string(not(containsString("pw-secret"))))
				.andExpect(jsonPath("$.whatsapp.enabled").value(true))
				.andExpect(jsonPath("$.whatsapp.phoneNumberId").value("1055"))
				.andExpect(jsonPath("$.whatsapp.accessTokenSet").value(true))
				.andExpect(jsonPath("$.jazzCash.merchantId").value("MC123"))
				.andExpect(jsonPath("$.jazzCash.passwordSet").value(true))
				.andExpect(jsonPath("$.easyPaisa.apiKeySet").value(false));

		String stored = jdbc.sql("select whatsapp_access_token_enc from integration_settings where clinic_id = :c")
				.param("c", doctor.getClinicId()).query(String.class).single();
		assertThat(stored).isNotEqualTo(secret).doesNotContain(secret);
		assertThat(cipher.decrypt(stored)).isEqualTo(secret);
	}

	@Test
	void omittingASecretKeepsItAndAnEmptyStringClearsIt() throws Exception {
		mvc.perform(put("/api/v1/settings/integrations").header(HttpHeaders.AUTHORIZATION, bearer(doctor))
				.contentType(MediaType.APPLICATION_JSON).content("{\"easyPaisaApiKey\":\"key-1\",\"easyPaisaStoreId\":\"S1\"}"))
				.andExpect(jsonPath("$.easyPaisa.apiKeySet").value(true));
		mvc.perform(put("/api/v1/settings/integrations").header(HttpHeaders.AUTHORIZATION, bearer(doctor))
				.contentType(MediaType.APPLICATION_JSON).content("{\"easyPaisaAccountNumber\":\"0300\"}"))
				.andExpect(jsonPath("$.easyPaisa.apiKeySet").value(true))
				.andExpect(jsonPath("$.easyPaisa.storeId").value("S1"))
				.andExpect(jsonPath("$.easyPaisa.accountNumber").value("0300"));
		mvc.perform(put("/api/v1/settings/integrations").header(HttpHeaders.AUTHORIZATION, bearer(doctor))
				.contentType(MediaType.APPLICATION_JSON).content("{\"easyPaisaApiKey\":\"\"}"))
				.andExpect(jsonPath("$.easyPaisa.apiKeySet").value(false));
	}

	@Test
	void anAssistantCannotSeeOrChangeIntegrations() throws Exception {
		mvc.perform(get("/api/v1/settings/integrations").header(HttpHeaders.AUTHORIZATION, bearer(assistant)))
				.andExpect(status().isForbidden());
		mvc.perform(put("/api/v1/settings/integrations").header(HttpHeaders.AUTHORIZATION, bearer(assistant))
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isForbidden());
	}

	// --- translations ---

	@Test
	void theSeededUrduDictionaryIsAvailableToEveryone() throws Exception {
		mvc.perform(get("/api/v1/translations?category=symptom").header(HttpHeaders.AUTHORIZATION, bearer(assistant)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.sourceText=='fever')].urduText", hasItem("بخار")));
		mvc.perform(get("/api/v1/translations").header(HttpHeaders.AUTHORIZATION, bearer(assistant)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(113));
	}

	@Test
	void aDoctorAddsAndReplacesATranslationIgnoringCaseAndSpacing() throws Exception {
		mvc.perform(put("/api/v1/translations").header(HttpHeaders.AUTHORIZATION, bearer(doctor))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"category\":\"symptom\",\"sourceText\":\"Night Sweats\",\"urduText\":\"رات کو پسینہ\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.urduText").value("رات کو پسینہ"));
		mvc.perform(put("/api/v1/translations").header(HttpHeaders.AUTHORIZATION, bearer(doctor))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"category\":\"symptom\",\"sourceText\":\"  night   SWEATS \",\"urduText\":\"رات میں پسینہ\"}"))
				.andExpect(status().isOk());
		mvc.perform(get("/api/v1/translations?category=symptom").header(HttpHeaders.AUTHORIZATION, bearer(assistant)))
				.andExpect(jsonPath("$.length()").value(16))
				.andExpect(jsonPath("$[?(@.sourceText=='Night Sweats')].urduText", hasItem("رات میں پسینہ")));
	}

	@Test
	void aTranslationCanBeDeleted() throws Exception {
		mvc.perform(delete("/api/v1/translations?category=symptom&sourceText=Fever")
				.header(HttpHeaders.AUTHORIZATION, bearer(doctor)))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/translations?category=symptom").header(HttpHeaders.AUTHORIZATION, bearer(doctor)))
				.andExpect(jsonPath("$.length()").value(14));
	}

	@Test
	void anAssistantCannotEditTranslationsAndCategoriesAreChecked() throws Exception {
		mvc.perform(put("/api/v1/translations").header(HttpHeaders.AUTHORIZATION, bearer(assistant))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"category\":\"symptom\",\"sourceText\":\"x\",\"urduText\":\"y\"}"))
				.andExpect(status().isForbidden());
		mvc.perform(put("/api/v1/translations").header(HttpHeaders.AUTHORIZATION, bearer(doctor))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"category\":\"weather\",\"sourceText\":\"x\",\"urduText\":\"y\"}"))
				.andExpect(status().isBadRequest());
	}

}
