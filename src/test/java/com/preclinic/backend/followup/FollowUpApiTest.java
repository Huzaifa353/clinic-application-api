package com.preclinic.backend.followup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.preclinic.backend.ApiIntegrationTest;
import com.preclinic.backend.user.AppUser;
import com.preclinic.backend.user.Role;

class FollowUpApiTest extends ApiIntegrationTest {

	AppUser doctor;
	AppUser assistant;
	String asDoctor;
	String asAssistant;
	LocalDate today;
	long ayesha;
	long bilal;
	long fatima;
	long dueToday;
	long overdue;
	long upcoming;

	@BeforeEach
	void setUp() {
		doctor = newUser("doc@test.local", "Dr. Test", Role.DOCTOR);
		assistant = newUser("asst@test.local", "Ayesha Assistant", Role.ASSISTANT);
		asDoctor = bearer(doctor);
		asAssistant = bearer(assistant);
		today = clock.today();
		ayesha = data.patient("Ayesha Khan", "03001122334");
		bilal = data.patient("Bilal Hassan", "03214455667");
		fatima = data.patient("Fatima Raza", "03335566778");
		dueToday = data.followUpFor(ayesha, doctor.getId(), today.minusDays(7), today);
		overdue = data.followUpFor(bilal, doctor.getId(), today.minusDays(20), today.minusDays(5));
		upcoming = data.followUpFor(fatima, doctor.getId(), today.minusDays(1), today.plusDays(7));
		diagnose(dueToday, "Viral Fever");
		diagnose(overdue, "Hypertension");
	}

	private void diagnose(long followUpId, String name) {
		jdbc.sql("insert into consultation_diagnosis (consultation_id, position, name) "
				+ "select consultation_id, 0, :n from follow_up where id = :id").param("n", name).param("id", followUpId).update();
	}

	private ResultActions list(String... queryPairs) throws Exception {
		var request = get("/api/v1/follow-ups").header(HttpHeaders.AUTHORIZATION, asAssistant);
		for (int i = 0; i < queryPairs.length; i += 2) {
			request = request.param(queryPairs[i], queryPairs[i + 1]);
		}
		return mvc.perform(request);
	}

	private ResultActions act(long id, String action, String as) throws Exception {
		return mvc.perform(post("/api/v1/follow-ups/" + id + "/" + action).header(HttpHeaders.AUTHORIZATION, as)
				.contentType(MediaType.APPLICATION_JSON).content("{}"));
	}

	// ===== tabs, search, filters =====

	@Test
	void theTabsSplitFollowUpsByDueDate() throws Exception {
		list("tab", "due").andExpect(jsonPath("$.data[*].id", contains((int) dueToday)))
				.andExpect(jsonPath("$.data[0].state").value("Due"))
				.andExpect(jsonPath("$.data[0].patientName").value("Ayesha Khan"))
				.andExpect(jsonPath("$.data[0].diagnoses", contains("Viral Fever")))
				.andExpect(jsonPath("$.data[0].daysOverdue").value(0));
		list("tab", "overdue").andExpect(jsonPath("$.data[*].id", contains((int) overdue)))
				.andExpect(jsonPath("$.data[0].state").value("Overdue"))
				.andExpect(jsonPath("$.data[0].daysOverdue").value(5));
		list("tab", "upcoming").andExpect(jsonPath("$.data[*].id", contains((int) upcoming)))
				.andExpect(jsonPath("$.data[0].state").value("Upcoming"));
		list("tab", "completed").andExpect(jsonPath("$.totalData").value(0));
		list("tab", "all", "sort", "dueDate").andExpect(jsonPath("$.data[*].id", contains((int) overdue, (int) dueToday, (int) upcoming)));
	}

	@Test
	void anItemCarriesTheOriginalVisitAndConsultation() throws Exception {
		list("tab", "due").andExpect(jsonPath("$.data[0].consultationDisplayId").value("C00001"))
				.andExpect(jsonPath("$.data[0].visitDate").value(today.minusDays(7).toString()))
				.andExpect(jsonPath("$.data[0].doctorName").value("Dr. Test"))
				.andExpect(jsonPath("$.data[0].mobile").value("03001122334"))
				.andExpect(jsonPath("$.data[0].days").value(7))
				.andExpect(jsonPath("$.data[0].contactStatus").doesNotExist())
				.andExpect(jsonPath("$.data[0].remindedToday").value(false));
	}

	@Test
	void theStatsMatchTheTabs() throws Exception {
		mvc.perform(get("/api/v1/follow-ups/stats").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.dueToday").value(1))
				.andExpect(jsonPath("$.overdue").value(1))
				.andExpect(jsonPath("$.upcoming").value(1))
				.andExpect(jsonPath("$.completed").value(0));
		act(upcoming, "complete", asAssistant).andExpect(status().isOk());
		mvc.perform(get("/api/v1/follow-ups/stats").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.upcoming").value(0))
				.andExpect(jsonPath("$.completed").value(1));
	}

	@Test
	void searchFindsPatientsByNameMobileNumberAndDiagnosis() throws Exception {
		list("q", "ayesha").andExpect(jsonPath("$.data[*].id", contains((int) dueToday)));
		list("q", "p0000").andExpect(jsonPath("$.totalData").value(3));
		list("q", "0321-4455667").andExpect(jsonPath("$.data[*].id", contains((int) overdue)));
		list("q", "hypertension").andExpect(jsonPath("$.data[*].id", contains((int) overdue)));
		list("q", "%").andExpect(jsonPath("$.totalData").value(0));
		list("q", "nobody").andExpect(jsonPath("$.totalData").value(0));
	}

	@Test
	void filtersByDoctorContactStatusAndDueDateRange() throws Exception {
		AppUser second = newUser("second@test.local", "Dr. Second", Role.DOCTOR);
		long other = data.patient("Other Patient", "03000000081");
		data.followUpFor(other, second.getId(), today.minusDays(3), today.plusDays(20));

		list("doctorId", String.valueOf(second.getId())).andExpect(jsonPath("$.totalData").value(1));
		list("doctorId", String.valueOf(doctor.getId())).andExpect(jsonPath("$.totalData").value(3));

		act(overdue, "remind", asAssistant);
		mvc.perform(put("/api/v1/follow-ups/" + overdue + "/contact-status").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"no-response\"}")).andExpect(status().isOk());
		list("contact", "no-response").andExpect(jsonPath("$.data[*].id", contains((int) overdue)));
		list("contact", "none").andExpect(jsonPath("$.totalData").value(3));

		list("from", today.toString(), "to", today.plusDays(7).toString(), "sort", "dueDate")
				.andExpect(jsonPath("$.data[*].id", contains((int) dueToday, (int) upcoming)));
	}

	@Test
	void sortingAndPaging() throws Exception {
		list("sort", "patient", "dir", "asc", "limit", "2").andExpect(jsonPath("$.totalData").value(3))
				.andExpect(jsonPath("$.data[*].patientName", contains("Ayesha Khan", "Bilal Hassan")));
		list("sort", "patient", "dir", "asc", "limit", "2", "skip", "2")
				.andExpect(jsonPath("$.data[*].patientName", contains("Fatima Raza")));
		list("sort", "nonsense; drop").andExpect(status().isBadRequest());
		list("tab", "weird").andExpect(status().isBadRequest());
	}

	@Test
	void theDashboardListHoldsOnlyOpenDueAndOverdueOldestFirst() throws Exception {
		mvc.perform(get("/api/v1/follow-ups/due").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$[*].id", contains((int) overdue, (int) dueToday)));
		act(overdue, "complete", asAssistant);
		mvc.perform(get("/api/v1/follow-ups/due").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$[*].id", contains((int) dueToday)));
	}

	// ===== actions =====

	@Test
	void aReminderIsStampedForToday() throws Exception {
		act(dueToday, "remind", asAssistant).andExpect(status().isOk())
				.andExpect(jsonPath("$.lastRemindedOn").value(today.toString()))
				.andExpect(jsonPath("$.remindedToday").value(true))
				.andExpect(jsonPath("$.contactStatus").doesNotExist());
	}

	@Test
	void recordingTheContactOutcomeAlsoCountsAsAReminder() throws Exception {
		mvc.perform(put("/api/v1/follow-ups/" + dueToday + "/contact-status").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"contacted\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.contactStatus").value("contacted"))
				.andExpect(jsonPath("$.remindedToday").value(true));
		mvc.perform(put("/api/v1/follow-ups/" + dueToday + "/contact-status").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"maybe\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void reschedulingMovesTheDueDateAndKeepsTheOriginal() throws Exception {
		LocalDate later = today.plusDays(10);
		mvc.perform(post("/api/v1/follow-ups/" + overdue + "/reschedule").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"" + later + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.dueDate").value(later.toString()))
				.andExpect(jsonPath("$.originalDueDate").value(today.minusDays(5).toString()))
				.andExpect(jsonPath("$.state").value("Upcoming"))
				.andExpect(jsonPath("$.daysOverdue").value(0));
		list("tab", "overdue").andExpect(jsonPath("$.totalData").value(0));
		mvc.perform(post("/api/v1/follow-ups/" + overdue + "/reschedule").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"" + today.minusDays(1) + "\"}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("FOLLOW_UP_DATE_IN_PAST"));
	}

	@Test
	void completingAndCancellingCloseTheFollowUp() throws Exception {
		act(dueToday, "complete", asDoctor).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("completed"))
				.andExpect(jsonPath("$.state").value("Completed"))
				.andExpect(jsonPath("$.resolvedAt").exists());
		mvc.perform(post("/api/v1/follow-ups/" + overdue + "/cancel").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Patient moved city\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("cancelled"))
				.andExpect(jsonPath("$.cancellationReason").value("Patient moved city"));
		list("tab", "completed").andExpect(jsonPath("$.totalData").value(2));
		list("tab", "overdue").andExpect(jsonPath("$.totalData").value(0));
	}

	@Test
	void aClosedFollowUpCannotBeWorkedAgain() throws Exception {
		act(dueToday, "complete", asAssistant);
		for (String action : new String[] { "remind", "complete", "cancel", "reschedule", "schedule-appointment" }) {
			mvc.perform(post("/api/v1/follow-ups/" + dueToday + "/" + action).header(HttpHeaders.AUTHORIZATION, asAssistant)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"date\":\"" + today.plusDays(3) + "\",\"time\":\"10:00\"}"))
					.andExpect(status().isUnprocessableEntity())
					.andExpect(jsonPath("$.code").value("FOLLOW_UP_CLOSED"));
		}
	}

	@Test
	void anUnknownFollowUpIs404() throws Exception {
		act(999999, "complete", asAssistant).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FOLLOW_UP_NOT_FOUND"));
		mvc.perform(get("/api/v1/follow-ups/999999").header(HttpHeaders.AUTHORIZATION, asAssistant)).andExpect(status().isNotFound());
	}

	// ===== scheduling =====

	@Test
	void schedulingBooksAFollowUpAppointmentAndLinksIt() throws Exception {
		LocalDate day = today.plusDays(2);
		mvc.perform(post("/api/v1/follow-ups/" + dueToday + "/schedule-appointment").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"" + day + "\",\"time\":\"10:30\",\"notes\":\"Review\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.linkedAppointmentId").exists())
				.andExpect(jsonPath("$.linkedAppointmentDate").value(day.toString()))
				.andExpect(jsonPath("$.linkedAppointmentTime").value("10:30"))
				.andExpect(jsonPath("$.linkedAppointmentStatus").value("scheduled"))
				.andExpect(jsonPath("$.state").value("Due"));

		mvc.perform(get("/api/v1/appointments").param("patientId", String.valueOf(ayesha)).header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.data", hasSize(1)))
				.andExpect(jsonPath("$.data[0].type").value("Follow-up"))
				.andExpect(jsonPath("$.data[0].notes").value("Review"));
	}

	@Test
	void schedulingFollowsTheAppointmentRules() throws Exception {
		LocalDate day = today.plusDays(2);
		String body = "{\"date\":\"" + day + "\",\"time\":\"10:30\"}";
		// a past date
		mvc.perform(post("/api/v1/follow-ups/" + dueToday + "/schedule-appointment").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"" + today.minusDays(1) + "\",\"time\":\"10:30\"}"))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("APPOINTMENT_IN_PAST"));
		// a doctor slot someone else already holds
		mvc.perform(post("/api/v1/follow-ups/" + dueToday + "/schedule-appointment").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
		mvc.perform(post("/api/v1/follow-ups/" + overdue + "/schedule-appointment").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APPOINTMENT_SLOT_TAKEN"));
		// the same patient already booked that day needs confirmation
		mvc.perform(post("/api/v1/follow-ups/" + dueToday + "/schedule-appointment").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"" + day + "\",\"time\":\"15:00\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PATIENT_ALREADY_BOOKED"));
		mvc.perform(post("/api/v1/follow-ups/" + dueToday + "/schedule-appointment").param("force", "true")
				.header(HttpHeaders.AUTHORIZATION, asAssistant).contentType(MediaType.APPLICATION_JSON)
				.content("{\"date\":\"" + day + "\",\"time\":\"15:00\"}")).andExpect(status().isOk());
	}

	// ===== integration with the consultation =====

	@Test
	void aFollowUpPlannedInTheConsultationShowsUpHereWithTheRightDate() throws Exception {
		// the fixtures numbered their consultations directly; move the real counter past them
		jdbc.sql("insert into number_sequence (clinic_id, kind, scope, last_value) values (:c, 'consultation', '', 100)")
				.param("c", defaultClinic().getId()).update();
		long visit = ((Number) JsonPath.read(mvc.perform(post("/api/v1/queue/intake").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"patientId\":" + data.patient("Real Flow", "03000000091") + ",\"consultationFee\":500}"))
				.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/queue/" + visit + "/status")
				.header(HttpHeaders.AUTHORIZATION, asDoctor).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"consulting\"}"));
		mvc.perform(put("/api/v1/visits/" + visit + "/consultation").header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"diagnoses\":[\"Migraine\"],\"followUp\":{\"enabled\":true,\"days\":10,\"reason\":\"Recheck\"}}"))
				.andExpect(status().isOk());
		list("q", "migraine").andExpect(jsonPath("$.totalData").value(1))
				.andExpect(jsonPath("$.data[0].dueDate").value(today.plusDays(10).toString()))
				.andExpect(jsonPath("$.data[0].reason").value("Recheck"))
				.andExpect(jsonPath("$.data[0].state").value("Upcoming"));
		assertThat(jdbc.sql("select count(*) from follow_up").query(Long.class).single()).isEqualTo(4);
	}

	@Test
	void anotherClinicSeesAndTouchesNothing() throws Exception {
		String asStranger = bearer(newUser(otherClinic().getId(), "stranger@other.local", "Stranger", Role.ASSISTANT));
		mvc.perform(get("/api/v1/follow-ups").header(HttpHeaders.AUTHORIZATION, asStranger)).andExpect(jsonPath("$.totalData").value(0));
		mvc.perform(get("/api/v1/follow-ups/due").header(HttpHeaders.AUTHORIZATION, asStranger)).andExpect(jsonPath("$", hasSize(0)));
		mvc.perform(get("/api/v1/follow-ups/stats").header(HttpHeaders.AUTHORIZATION, asStranger)).andExpect(jsonPath("$.overdue").value(0));
		for (String action : new String[] { "remind", "complete", "cancel" }) {
			act(dueToday, action, asStranger).andExpect(status().isNotFound());
		}
	}

	@Test
	void everythingNeedsALogin() throws Exception {
		mvc.perform(get("/api/v1/follow-ups")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/follow-ups/1/complete")).andExpect(status().isUnauthorized());
	}
}
