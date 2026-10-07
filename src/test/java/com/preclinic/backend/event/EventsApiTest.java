package com.preclinic.backend.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;
import com.preclinic.backend.CommittedDataTest;

/** Live updates: ticket handshake, delivery after commit only, and clinic isolation. */
class EventsApiTest extends CommittedDataTest {

	@Autowired
	EventBus bus;

	private String ticket(String as) throws Exception {
		String body = mvc.perform(post("/api/v1/events/ticket").header(HttpHeaders.AUTHORIZATION, as))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.expiresInSeconds").value(30))
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.ticket");
	}

	private MvcResult openStream(String as) throws Exception {
		return mvc.perform(get("/api/v1/events").param("ticket", ticket(as)))
				.andExpect(request().asyncStarted())
				.andReturn();
	}

	@Test
	void aTicketIsRequiredAndWorksOnlyOnce() throws Exception {
		mvc.perform(post("/api/v1/events/ticket")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/events")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/events").param("ticket", "made-up"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_TICKET"));

		String ticket = ticket(asAssistant);
		mvc.perform(get("/api/v1/events").param("ticket", ticket)).andExpect(request().asyncStarted());
		mvc.perform(get("/api/v1/events").param("ticket", ticket))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_TICKET"));
	}

	@Test
	void theStreamAnnouncesChangesAfterTheyAreCommitted() throws Exception {
		MvcResult stream = openStream(asDoctor);
		assertThat(stream.getResponse().getContentAsString()).contains("event:connected");

		long patient = commitPatient("Live Patient", "03001230000");
		mvc.perform(post("/api/v1/queue/intake").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"patientId\":" + patient + ",\"consultationFee\":1500,\"amountPaid\":1500}"))
				.andExpect(status().isCreated());

		String received = stream.getResponse().getContentAsString();
		assertThat(received).contains("event:queue").contains("event:invoice");
	}

	@Test
	void aRolledBackChangeAnnouncesNothing() throws Exception {
		MvcResult stream = openStream(asDoctor);
		tx().executeWithoutResult(status -> {
			bus.publish(clinicId, EventBus.Type.QUEUE);
			status.setRollbackOnly();
		});
		assertThat(stream.getResponse().getContentAsString()).doesNotContain("event:queue");

		tx().executeWithoutResult(status -> bus.publish(clinicId, EventBus.Type.QUEUE));
		assertThat(stream.getResponse().getContentAsString()).contains("event:queue");
	}

	@Test
	void otherClinicsDoNotHearOurEvents() throws Exception {
		MvcResult stream = openStream(asDoctor);
		bus.publish(clinicId + 1000, EventBus.Type.QUEUE);
		assertThat(stream.getResponse().getContentAsString()).doesNotContain("event:queue");
		bus.publish(clinicId, EventBus.Type.APPOINTMENTS);
		assertThat(stream.getResponse().getContentAsString()).contains("event:appointments");
	}

	@Test
	void everyConnectedScreenOfTheClinicGetsTheEvent() throws Exception {
		MvcResult doctorScreen = openStream(asDoctor);
		MvcResult assistantScreen = openStream(asAssistant);
		bus.publish(clinicId, EventBus.Type.DOCTOR_STATUS);
		assertThat(doctorScreen.getResponse().getContentAsString()).contains("event:doctor-status");
		assertThat(assistantScreen.getResponse().getContentAsString()).contains("event:doctor-status");
	}

	@Test
	void changingTheDoctorsAwayFlagAnnouncesIt() throws Exception {
		MvcResult stream = openStream(asAssistant);
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/doctor-status")
				.header(HttpHeaders.AUTHORIZATION, asDoctor).contentType(MediaType.APPLICATION_JSON).content("{\"away\":true}"))
				.andExpect(status().isOk());
		assertThat(stream.getResponse().getContentAsString()).contains("event:doctor-status");
	}
}
