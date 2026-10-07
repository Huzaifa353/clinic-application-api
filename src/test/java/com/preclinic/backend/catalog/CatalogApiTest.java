package com.preclinic.backend.catalog;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.preclinic.backend.ApiIntegrationTest;
import com.preclinic.backend.user.AppUser;
import com.preclinic.backend.user.Role;

class CatalogApiTest extends ApiIntegrationTest {

	AppUser doctor;
	AppUser assistant;

	@BeforeEach
	void createUsers() {
		doctor = newUser("doc@test.local", "Dr. Test", Role.DOCTOR);
		assistant = newUser("asst@test.local", "Ayesha Assistant", Role.ASSISTANT);
	}

	private String auth(AppUser user) {
		return bearer(user);
	}

	// --- bootstrap ---

	@Test
	void bootstrapReturnsEveryListTheConsultationRoomNeeds() throws Exception {
		mvc.perform(get("/api/v1/catalog/bootstrap").header(HttpHeaders.AUTHORIZATION, auth(assistant)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.symptoms", hasSize(15)))
				.andExpect(jsonPath("$.symptoms", hasItem("Fever")))
				.andExpect(jsonPath("$.diagnoses", hasSize(12)))
				.andExpect(jsonPath("$.investigations", hasSize(24)))
				.andExpect(jsonPath("$.investigations[0].id").value("cbc"))
				.andExpect(jsonPath("$.investigations[0].name").value("CBC"))
				.andExpect(jsonPath("$.investigations[0].fullName").value("Complete Blood Count"))
				.andExpect(jsonPath("$.investigations[0].group").value("Laboratory"))
				.andExpect(jsonPath("$.frequencyCodes", hasSize(13)))
				.andExpect(jsonPath("$.frequencyCodes[0].code").value("1-0-0"))
				.andExpect(jsonPath("$.frequencyCodes[0].meaning").value("Once daily — morning"))
				.andExpect(jsonPath("$.durationOptions[0]").value("3 Days"))
				.andExpect(jsonPath("$.timingOptions", hasSize(12)));
	}

	@Test
	void examinationCategoriesKeepTheirOrderAndEndWithOther() throws Exception {
		mvc.perform(get("/api/v1/catalog/bootstrap").header(HttpHeaders.AUTHORIZATION, auth(doctor)))
				.andExpect(jsonPath("$.examinationCategories", contains("General", "Chest / Respiratory",
						"CVS / Cardiovascular", "Abdomen", "CNS / Neurological", "ENT", "Musculoskeletal", "Skin",
						"Other")))
				.andExpect(jsonPath("$.examinationFindings.General[0]").value("Normal"))
				.andExpect(jsonPath("$.examinationFindings.ENT", hasItem("Throat Congested")))
				.andExpect(jsonPath("$.examinationFindings.Other", hasSize(0)));
	}

	@Test
	void bootstrapNeedsALogin() throws Exception {
		mvc.perform(get("/api/v1/catalog/bootstrap")).andExpect(status().isUnauthorized());
	}

	// --- adding symptoms / diagnoses on the fly ---

	@Test
	void aDoctorAddsASymptomAndItAppearsInTheList() throws Exception {
		mvc.perform(post("/api/v1/catalog/symptoms").header(HttpHeaders.AUTHORIZATION, auth(doctor))
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  Night   Sweats \"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Night Sweats"))
				.andExpect(jsonPath("$.created").value(true));
		mvc.perform(get("/api/v1/catalog/bootstrap").header(HttpHeaders.AUTHORIZATION, auth(assistant)))
				.andExpect(jsonPath("$.symptoms", hasSize(16)))
				.andExpect(jsonPath("$.symptoms", hasItem("Night Sweats")));
	}

	@Test
	void addingAnExistingNameIgnoringCaseReturnsTheExistingSpelling() throws Exception {
		mvc.perform(post("/api/v1/catalog/diagnoses").header(HttpHeaders.AUTHORIZATION, auth(doctor))
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"viral fever\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Viral Fever"))
				.andExpect(jsonPath("$.created").value(false));
		mvc.perform(get("/api/v1/catalog/bootstrap").header(HttpHeaders.AUTHORIZATION, auth(doctor)))
				.andExpect(jsonPath("$.diagnoses", hasSize(12)));
	}

	@Test
	void anAssistantCannotAddToTheClinicalCatalog() throws Exception {
		mvc.perform(post("/api/v1/catalog/symptoms").header(HttpHeaders.AUTHORIZATION, auth(assistant))
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Anything\"}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void aBlankNameIsRejected() throws Exception {
		mvc.perform(post("/api/v1/catalog/symptoms").header(HttpHeaders.AUTHORIZATION, auth(doctor))
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"   \"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	// --- service fees ---

	@Test
	void bothRolesSeeTheSeededServiceFees() throws Exception {
		mvc.perform(get("/api/v1/service-fees").header(HttpHeaders.AUTHORIZATION, auth(assistant)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(5)))
				.andExpect(jsonPath("$[0].name").value("Consultation"))
				.andExpect(jsonPath("$[0].fee").value(1500.0))
				.andExpect(jsonPath("$[4].name").value("Other"));
	}

	@Test
	void aDoctorCreatesEditsAndRemovesAService() throws Exception {
		String created = mvc.perform(post("/api/v1/service-fees").header(HttpHeaders.AUTHORIZATION, auth(doctor))
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ECG\",\"fee\":800}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("ECG"))
				.andReturn().getResponse().getContentAsString();
		long id = ((Number) com.jayway.jsonpath.JsonPath.read(created, "$.id")).longValue();

		mvc.perform(put("/api/v1/service-fees/" + id).header(HttpHeaders.AUTHORIZATION, auth(doctor))
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ECG (12 lead)\",\"fee\":900.50}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fee").value(900.5));
		mvc.perform(get("/api/v1/service-fees").header(HttpHeaders.AUTHORIZATION, auth(assistant)))
				.andExpect(jsonPath("$", hasSize(6)))
				.andExpect(jsonPath("$[5].name").value("ECG (12 lead)"));

		mvc.perform(delete("/api/v1/service-fees/" + id).header(HttpHeaders.AUTHORIZATION, auth(doctor)))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/service-fees").header(HttpHeaders.AUTHORIZATION, auth(assistant)))
				.andExpect(jsonPath("$", hasSize(5)));
		mvc.perform(delete("/api/v1/service-fees/" + id).header(HttpHeaders.AUTHORIZATION, auth(doctor)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SERVICE_NOT_FOUND"));
	}

	@Test
	void anAssistantCannotChangeFees() throws Exception {
		mvc.perform(post("/api/v1/service-fees").header(HttpHeaders.AUTHORIZATION, auth(assistant))
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Free\",\"fee\":0}"))
				.andExpect(status().isForbidden());
		mvc.perform(delete("/api/v1/service-fees/1").header(HttpHeaders.AUTHORIZATION, auth(assistant)))
				.andExpect(status().isForbidden());
	}

	@Test
	void feeValidationRejectsNegativesAndMissingValues() throws Exception {
		mvc.perform(post("/api/v1/service-fees").header(HttpHeaders.AUTHORIZATION, auth(doctor))
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Bad\",\"fee\":-5}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/service-fees").header(HttpHeaders.AUTHORIZATION, auth(doctor))
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Bad\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void updatingAnUnknownServiceIs404() throws Exception {
		mvc.perform(put("/api/v1/service-fees/999999").header(HttpHeaders.AUTHORIZATION, auth(doctor))
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\",\"fee\":1}"))
				.andExpect(status().isNotFound());
	}
}
