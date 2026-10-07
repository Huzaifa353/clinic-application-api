package com.preclinic.backend.consultation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;
import com.preclinic.backend.CommittedDataTest;

/**
 * The doctor's flow with real commits: a rejected save leaves no half-written consultation, and a
 * whole clinic day (intake -> call -> consult -> next) hangs together.
 */
class ConsultationCommittedTest extends CommittedDataTest {

	private long intakeAndCall(long patient) throws Exception {
		String body = mvc.perform(post("/api/v1/queue/intake").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"patientId\":" + patient + ",\"consultationFee\":1500}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	private long count(String table) {
		return jdbc.sql("select count(*) from " + table).query(Long.class).single();
	}

	@Test
	void aRejectedSaveLeavesNothingHalfWritten() throws Exception {
		long patient = commitPatient("Rollback Patient", "03007770001");
		long visit = intakeAndCall(patient);
		mvc.perform(post("/api/v1/queue/call-next").header(HttpHeaders.AUTHORIZATION, asDoctor)).andExpect(status().isOk());

		// symptoms, diagnoses, examination and the consultation row are written before the unknown
		// medicine is discovered - all of it must disappear
		String bad = "{\"symptoms\":[\"Fever\"],\"diagnoses\":[\"Viral Fever\"],\"notes\":\"n\","
				+ "\"examinationFindings\":[{\"category\":\"ENT\",\"finding\":\"Throat Congested\"}],"
				+ "\"followUp\":{\"enabled\":true,\"days\":7},"
				+ "\"prescription\":[{\"medicine\":\"Ghost\",\"productId\":999999}]}";
		mvc.perform(put("/api/v1/visits/" + visit + "/consultation").header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON).content(bad))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("UNKNOWN_MEDICINE"));

		assertThat(count("consultation")).isZero();
		assertThat(count("consultation_symptom")).isZero();
		assertThat(count("consultation_diagnosis")).isZero();
		assertThat(count("consultation_examination")).isZero();
		assertThat(count("follow_up")).isZero();
		assertThat(jdbc.sql("select status from visit where id = :v").param("v", visit).query(String.class).single())
				.isEqualTo("consulting");

		// the consultation number was not used up either
		mvc.perform(put("/api/v1/visits/" + visit + "/consultation").header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON).content("{\"symptoms\":[\"Fever\"]}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.displayId").value("C00001"));
	}

	@Test
	void aWholeClinicDayHangsTogether() throws Exception {
		long first = commitPatient("First Patient", "03007770011");
		long second = commitPatient("Second Patient", "03007770012");
		long firstVisit = intakeAndCall(first);
		long secondVisit = intakeAndCall(second);

		// the doctor takes the first patient
		mvc.perform(post("/api/v1/queue/call-next").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.id").value(firstVisit));
		mvc.perform(put("/api/v1/visits/" + firstVisit + "/consultation").header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"diagnoses\":[\"Hypertension\"],\"followUp\":{\"enabled\":true,\"days\":14}}"))
				.andExpect(status().isOk());

		// the doctor is free again, so the second can be called, and the assistant was told both times
		mvc.perform(post("/api/v1/queue/call-next").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.id").value(secondVisit));
		assertThat(count("notification")).isEqualTo(2);
		assertThat(jdbc.sql("select count(*) from visit where status = 'consulting'").query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("select count(*) from visit where status = 'completed'").query(Long.class).single()).isEqualTo(1);
		assertThat(count("follow_up")).isEqualTo(1);

		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/patients/stats")
				.header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.total").value(2));
	}
}
