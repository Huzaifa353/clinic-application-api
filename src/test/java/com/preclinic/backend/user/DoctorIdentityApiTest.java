package com.preclinic.backend.user;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.preclinic.backend.ApiIntegrationTest;

class DoctorIdentityApiTest extends ApiIntegrationTest {

	AppUser doctor;
	AppUser assistant;

	@BeforeEach
	void createUsers() {
		doctor = newUser("doc@test.local", "Dr. Test", Role.DOCTOR);
		assistant = newUser("asst@test.local", "Ayesha Assistant", Role.ASSISTANT);
	}

	// --- users ---

	@Test
	void usersListsTheClinicAndFiltersByRole() throws Exception {
		mvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, bearer(assistant)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(2)));
		mvc.perform(get("/api/v1/users?role=doctor").header(HttpHeaders.AUTHORIZATION, bearer(assistant)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].fullName", contains("Dr. Test")))
				.andExpect(jsonPath("$[0].role").value("doctor"));
	}

	@Test
	void usersNeverIncludesOtherClinicsOrInactiveAccounts() throws Exception {
		Clinic other = otherClinic();
		newUser(other.getId(), "stranger@other.local", "Stranger", Role.DOCTOR);
		AppUser retired = newUser("old@test.local", "Retired Dr", Role.DOCTOR);
		retired.setActive(false);
		users.saveAndFlush(retired);

		mvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, bearer(assistant)))
				.andExpect(jsonPath("$", hasSize(2)));
	}

	// --- doctor profile ---

	@Test
	void anAssistantReadsTheClinicDoctorsProfileWithoutKnowingTheId() throws Exception {
		mvc.perform(get("/api/v1/doctor-profile").header(HttpHeaders.AUTHORIZATION, bearer(assistant)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value(doctor.getId()))
				.andExpect(jsonPath("$.name").value("Dr. Test"))
				.andExpect(jsonPath("$.qualifications").doesNotExist());
	}

	@Test
	void aDoctorSavesTheirProfileAndItReadsBack() throws Exception {
		String body = """
				{"name":"Dr. Sara Ahmed","qualifications":"MBBS, FCPS","specialization":"General Practice",
				 "registrationNo":"PMC-45217-P","city":"Islamabad","country":"Pakistan",
				 "preferredPrintLanguage":"bilingual"}""";
		mvc.perform(put("/api/v1/doctor-profile").header(HttpHeaders.AUTHORIZATION, bearer(doctor))
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Dr. Sara Ahmed"))
				.andExpect(jsonPath("$.registrationNo").value("PMC-45217-P"));

		// the assistant (who prints documents) sees the same data
		mvc.perform(get("/api/v1/doctor-profile").header(HttpHeaders.AUTHORIZATION, bearer(assistant)))
				.andExpect(jsonPath("$.name").value("Dr. Sara Ahmed"))
				.andExpect(jsonPath("$.qualifications").value("MBBS, FCPS"))
				.andExpect(jsonPath("$.preferredPrintLanguage").value("bilingual"));
	}

	@Test
	void savingAProfileClearsOmittedFieldsButKeepsTheName() throws Exception {
		mvc.perform(put("/api/v1/doctor-profile").header(HttpHeaders.AUTHORIZATION, bearer(doctor))
				.contentType(MediaType.APPLICATION_JSON).content("{\"qualifications\":\"MBBS\",\"city\":\"Lahore\"}"))
				.andExpect(status().isOk());
		mvc.perform(put("/api/v1/doctor-profile").header(HttpHeaders.AUTHORIZATION, bearer(doctor))
				.contentType(MediaType.APPLICATION_JSON).content("{\"city\":\"Karachi\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Dr. Test"))
				.andExpect(jsonPath("$.qualifications").doesNotExist())
				.andExpect(jsonPath("$.city").value("Karachi"));
	}

	@Test
	void anAssistantCannotEditTheDoctorProfile() throws Exception {
		mvc.perform(put("/api/v1/doctor-profile").header(HttpHeaders.AUTHORIZATION, bearer(assistant))
				.contentType(MediaType.APPLICATION_JSON).content("{\"city\":\"Hacked\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	void anInvalidPrintLanguageIsRejected() throws Exception {
		mvc.perform(put("/api/v1/doctor-profile").header(HttpHeaders.AUTHORIZATION, bearer(doctor))
				.contentType(MediaType.APPLICATION_JSON).content("{\"preferredPrintLanguage\":\"french\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("preferredPrintLanguage"));
	}

	@Test
	void aDoctorOfAnotherClinicIsInvisible() throws Exception {
		Clinic other = otherClinic();
		AppUser stranger = newUser(other.getId(), "stranger@other.local", "Stranger", Role.DOCTOR);
		mvc.perform(get("/api/v1/doctor-profile?doctorId=" + stranger.getId())
				.header(HttpHeaders.AUTHORIZATION, bearer(assistant)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("DOCTOR_NOT_FOUND"));
	}

	@Test
	void anAssistantIdIsNotADoctorId() throws Exception {
		mvc.perform(get("/api/v1/doctor-profile?doctorId=" + assistant.getId())
				.header(HttpHeaders.AUTHORIZATION, bearer(assistant)))
				.andExpect(status().isNotFound());
	}

	// --- doctor status ---

	@Test
	void theDoctorIsNotAwayUntilTheySaySo() throws Exception {
		mvc.perform(get("/api/v1/doctor-status").header(HttpHeaders.AUTHORIZATION, bearer(assistant)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.doctorId").value(doctor.getId()))
				.andExpect(jsonPath("$.doctorName").value("Dr. Test"))
				.andExpect(jsonPath("$.away").value(false));
	}

	@Test
	void theDoctorTogglesAwayAndTheAssistantSeesIt() throws Exception {
		mvc.perform(put("/api/v1/doctor-status").header(HttpHeaders.AUTHORIZATION, bearer(doctor))
				.contentType(MediaType.APPLICATION_JSON).content("{\"away\":true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.away").value(true));
		mvc.perform(get("/api/v1/doctor-status").header(HttpHeaders.AUTHORIZATION, bearer(assistant)))
				.andExpect(jsonPath("$.away").value(true));
		mvc.perform(put("/api/v1/doctor-status").header(HttpHeaders.AUTHORIZATION, bearer(doctor))
				.contentType(MediaType.APPLICATION_JSON).content("{\"away\":false}"))
				.andExpect(jsonPath("$.away").value(false));
	}

	@Test
	void anAssistantCannotSetTheDoctorAway() throws Exception {
		mvc.perform(put("/api/v1/doctor-status").header(HttpHeaders.AUTHORIZATION, bearer(assistant))
				.contentType(MediaType.APPLICATION_JSON).content("{\"away\":true}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void awayIsRequired() throws Exception {
		mvc.perform(put("/api/v1/doctor-status").header(HttpHeaders.AUTHORIZATION, bearer(doctor))
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

}
