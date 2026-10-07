package com.preclinic.backend.appointment;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;
import com.preclinic.backend.ApiIntegrationTest;
import com.preclinic.backend.Fixtures;
import com.preclinic.backend.user.AppUser;
import com.preclinic.backend.user.Role;

class AppointmentApiTest extends ApiIntegrationTest {

	AppUser doctor;
	AppUser assistant;
	String asAssistant;
	String asDoctor;
	long ayesha;
	long bilal;
	LocalDate tomorrow;

	@BeforeEach
	void setUp() {
		doctor = newUser("doc@test.local", "Dr. Test", Role.DOCTOR);
		assistant = newUser("asst@test.local", "Ayesha Assistant", Role.ASSISTANT);
		asAssistant = bearer(assistant);
		asDoctor = bearer(doctor);
		ayesha = data.patient("Ayesha Khan", "03001122334");
		bilal = data.patient("Bilal Hassan", "03214455667");
		tomorrow = clock.today().plusDays(1);
	}

	private String body(long patientId, LocalDate date, String time) {
		return "{\"patientId\":" + patientId + ",\"date\":\"" + date + "\",\"time\":\"" + time
				+ "\",\"type\":\"Consultation\",\"notes\":\"first visit\"}";
	}

	private String book(long patientId, LocalDate date, String time) throws Exception {
		return mvc.perform(post("/api/v1/appointments").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content(body(patientId, date, time)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
	}

	private long idOf(String json) {
		return ((Number) JsonPath.read(json, "$.id")).longValue();
	}

	// --- booking ---

	@Test
	void bookingDefaultsToTheClinicDoctorAndFormatsTheTime() throws Exception {
		mvc.perform(post("/api/v1/appointments").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content(body(ayesha, tomorrow, "10:00")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.displayId").value("A00001"))
				.andExpect(jsonPath("$.patientName").value("Ayesha Khan"))
				.andExpect(jsonPath("$.mobile").value("03001122334"))
				.andExpect(jsonPath("$.doctorId").value(doctor.getId()))
				.andExpect(jsonPath("$.doctorName").value("Dr. Test"))
				.andExpect(jsonPath("$.date").value(tomorrow.toString()))
				.andExpect(jsonPath("$.time").value("10:00"))
				.andExpect(jsonPath("$.status").value("scheduled"))
				.andExpect(jsonPath("$.reschedulable").value(true))
				.andExpect(jsonPath("$.visitId").doesNotExist());
	}

	@Test
	void appointmentNumbersCountUp() throws Exception {
		book(ayesha, tomorrow, "10:00");
		String second = book(bilal, tomorrow, "10:30");
		org.assertj.core.api.Assertions.assertThat((String) JsonPath.read(second, "$.displayId")).isEqualTo("A00002");
	}

	@Test
	void todayIsAllowedButThePastIsNot() throws Exception {
		book(ayesha, clock.today(), "23:30");
		mvc.perform(post("/api/v1/appointments").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content(body(bilal, clock.today().minusDays(1), "10:00")))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("APPOINTMENT_IN_PAST"));
	}

	@Test
	void theDoctorCanBookToo() throws Exception {
		mvc.perform(post("/api/v1/appointments").header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON).content(body(ayesha, tomorrow, "11:00")))
				.andExpect(status().isCreated());
	}

	@Test
	void anUnknownOrForeignPatientIs404() throws Exception {
		mvc.perform(post("/api/v1/appointments").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content(body(999999, tomorrow, "10:00")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PATIENT_NOT_FOUND"));
		long stranger = new Fixtures(jdbc, otherClinic().getId()).patient("Stranger", "03007777777");
		mvc.perform(post("/api/v1/appointments").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content(body(stranger, tomorrow, "10:00")))
				.andExpect(status().isNotFound());
	}

	@Test
	void aTakenDoctorSlotIsRefused() throws Exception {
		book(ayesha, tomorrow, "10:00");
		mvc.perform(post("/api/v1/appointments").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content(body(bilal, tomorrow, "10:00")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("APPOINTMENT_SLOT_TAKEN"));
		// a different time, or the same time on another day, is fine
		book(bilal, tomorrow, "10:15");
		book(bilal, tomorrow.plusDays(1), "10:00");
	}

	@Test
	void aCancelledAppointmentReleasesItsSlot() throws Exception {
		long first = idOf(book(ayesha, tomorrow, "10:00"));
		mvc.perform(post("/api/v1/appointments/" + first + "/cancel").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(status().isOk());
		book(bilal, tomorrow, "10:00");
	}

	@Test
	void theSamePatientTwiceInADayNeedsConfirmation() throws Exception {
		book(ayesha, tomorrow, "10:00");
		mvc.perform(post("/api/v1/appointments").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content(body(ayesha, tomorrow, "15:00")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PATIENT_ALREADY_BOOKED"));
		mvc.perform(post("/api/v1/appointments").param("force", "true").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content(body(ayesha, tomorrow, "15:00")))
				.andExpect(status().isCreated());
	}

	@Test
	void theConflictsEndpointReportsBothKindsBeforeBooking() throws Exception {
		book(ayesha, tomorrow, "10:00");
		mvc.perform(get("/api/v1/appointments/conflicts").param("patientId", String.valueOf(ayesha))
				.param("date", tomorrow.toString()).param("time", "10:00").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.patientSameDay.time").value("10:00"))
				.andExpect(jsonPath("$.doctorSlot.patientName").value("Ayesha Khan"));
		mvc.perform(get("/api/v1/appointments/conflicts").param("patientId", String.valueOf(bilal))
				.param("date", tomorrow.toString()).param("time", "10:30").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.patientSameDay").doesNotExist())
				.andExpect(jsonPath("$.doctorSlot").doesNotExist());
	}

	@Test
	void bookingValidatesItsInput() throws Exception {
		mvc.perform(post("/api/v1/appointments").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"patientId\":" + ayesha + "}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		mvc.perform(post("/api/v1/appointments").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"patientId\":" + ayesha + ",\"date\":\"" + tomorrow + "\",\"time\":\"10:00\",\"type\":\"Nonsense\"}"))
				.andExpect(status().isBadRequest());
	}

	// --- status changes ---

	@Test
	void arrivedThenNoShowOrCancelledFollowTheLifecycle() throws Exception {
		long a = idOf(book(ayesha, tomorrow, "10:00"));
		mvc.perform(post("/api/v1/appointments/" + a + "/arrive").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.status").value("arrived"))
				.andExpect(jsonPath("$.reschedulable").value(true));
		mvc.perform(post("/api/v1/appointments/" + a + "/no-show").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.status").value("no-show"))
				.andExpect(jsonPath("$.reschedulable").value(false));
		// terminal states can't be changed again
		mvc.perform(post("/api/v1/appointments/" + a + "/cancel").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("APPOINTMENT_NOT_CHANGEABLE"));
		mvc.perform(post("/api/v1/appointments/" + a + "/arrive").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(status().isUnprocessableEntity());
	}

	@Test
	void cancellingRecordsTheReason() throws Exception {
		long a = idOf(book(ayesha, tomorrow, "10:00"));
		mvc.perform(post("/api/v1/appointments/" + a + "/cancel").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Patient requested cancellation\"}"))
				.andExpect(jsonPath("$.status").value("cancelled"))
				.andExpect(jsonPath("$.cancellationReason").value("Patient requested cancellation"));
	}

	@Test
	void aCheckedInAppointmentCannotBeCancelledFromHere() throws Exception {
		long a = idOf(book(ayesha, clock.today(), "23:00"));
		data.visitFromAppointment(a, ayesha, doctor.getId(), clock.today(), "waiting");
		mvc.perform(post("/api/v1/appointments/" + a + "/cancel").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("APPOINTMENT_NOT_CHANGEABLE"));
	}

	@Test
	void anUnknownOrForeignAppointmentIs404() throws Exception {
		mvc.perform(post("/api/v1/appointments/999999/cancel").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("APPOINTMENT_NOT_FOUND"));
		mvc.perform(get("/api/v1/appointments/999999").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(status().isNotFound());
	}

	// --- reschedule ---

	@Test
	void rescheduleKeepsTheOriginalAndLinksTheNewBooking() throws Exception {
		long a = idOf(book(ayesha, tomorrow, "10:00"));
		LocalDate later = tomorrow.plusDays(5);
		String moved = mvc.perform(post("/api/v1/appointments/" + a + "/reschedule")
				.header(HttpHeaders.AUTHORIZATION, asAssistant).contentType(MediaType.APPLICATION_JSON)
				.content("{\"date\":\"" + later + "\",\"time\":\"14:30\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.date").value(later.toString()))
				.andExpect(jsonPath("$.time").value("14:30"))
				.andExpect(jsonPath("$.status").value("scheduled"))
				.andExpect(jsonPath("$.rescheduledFromId").value(a))
				.andExpect(jsonPath("$.type").value("Consultation"))
				.andExpect(jsonPath("$.notes").value("first visit"))
				.andReturn().getResponse().getContentAsString();
		mvc.perform(get("/api/v1/appointments/" + a).header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.status").value("rescheduled"))
				.andExpect(jsonPath("$.rescheduledToId").value(idOf(moved)))
				.andExpect(jsonPath("$.reschedulable").value(false));
	}

	@Test
	void rescheduleCanReuseTheSameTimeOnAnotherDayAndTheOldSlotIsFree() throws Exception {
		long a = idOf(book(ayesha, tomorrow, "10:00"));
		mvc.perform(post("/api/v1/appointments/" + a + "/reschedule").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"date\":\"" + tomorrow.plusDays(1) + "\",\"time\":\"10:00\"}"))
				.andExpect(status().isOk());
		book(bilal, tomorrow, "10:00");
	}

	@Test
	void rescheduleIntoATakenSlotIsRefusedAndTheOriginalStaysBooked() throws Exception {
		long a = idOf(book(ayesha, tomorrow, "10:00"));
		book(bilal, tomorrow, "11:00");
		mvc.perform(post("/api/v1/appointments/" + a + "/reschedule").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"" + tomorrow + "\",\"time\":\"11:00\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("APPOINTMENT_SLOT_TAKEN"));
		mvc.perform(get("/api/v1/appointments/" + a).header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.status").value("scheduled"));
	}

	@Test
	void rescheduleRefusesThePastAndNonOpenAppointments() throws Exception {
		long a = idOf(book(ayesha, tomorrow, "10:00"));
		mvc.perform(post("/api/v1/appointments/" + a + "/reschedule").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"date\":\"" + clock.today().minusDays(2) + "\",\"time\":\"10:00\"}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("APPOINTMENT_IN_PAST"));
		mvc.perform(post("/api/v1/appointments/" + a + "/cancel").header(HttpHeaders.AUTHORIZATION, asAssistant));
		mvc.perform(post("/api/v1/appointments/" + a + "/reschedule").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"" + tomorrow + "\",\"time\":\"12:00\"}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("APPOINTMENT_NOT_CHANGEABLE"));
	}

	// --- list / stats ---

	@Test
	void theDayListIsOrderedByTimeAndFiltersApply() throws Exception {
		book(bilal, tomorrow, "11:30");
		long first = idOf(book(ayesha, tomorrow, "09:00"));
		book(bilal, tomorrow.plusDays(3), "09:00");
		mvc.perform(post("/api/v1/appointments/" + first + "/arrive").header(HttpHeaders.AUTHORIZATION, asAssistant));

		mvc.perform(get("/api/v1/appointments").param("date", tomorrow.toString()).header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.totalData").value(2))
				.andExpect(jsonPath("$.data[*].time", contains("09:00", "11:30")));
		mvc.perform(get("/api/v1/appointments").param("date", tomorrow.toString()).param("status", "arrived")
				.header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.data", hasSize(1)))
				.andExpect(jsonPath("$.data[0].patientName").value("Ayesha Khan"));
		mvc.perform(get("/api/v1/appointments").param("date", tomorrow.toString()).param("q", "bilal")
				.header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.data[*].patientName", contains("Bilal Hassan")));
		mvc.perform(get("/api/v1/appointments").param("date", tomorrow.toString()).param("type", "Review")
				.header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.totalData").value(0));
	}

	@Test
	void searchTreatsPercentAndUnderscoreLiterally() throws Exception {
		book(ayesha, tomorrow, "10:00");
		for (String wildcard : new String[] { "%", "_", "A_esha" }) {
			mvc.perform(get("/api/v1/appointments").param("date", tomorrow.toString()).param("q", wildcard)
					.header(HttpHeaders.AUTHORIZATION, asAssistant))
					.andExpect(jsonPath("$.totalData").value(0));
		}
		mvc.perform(get("/api/v1/appointments").param("date", tomorrow.toString()).param("q", "03001122334")
				.header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.totalData").value(1));
	}

	@Test
	void aRangeAndAPatientHistoryComeBackInTheRequestedOrder() throws Exception {
		book(ayesha, tomorrow, "10:00");
		book(ayesha, tomorrow.plusDays(2), "10:00");
		book(bilal, tomorrow.plusDays(9), "10:00");
		mvc.perform(get("/api/v1/appointments").param("from", tomorrow.toString())
				.param("to", tomorrow.plusDays(3).toString()).header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.totalData").value(2));
		mvc.perform(get("/api/v1/appointments").param("patientId", String.valueOf(ayesha)).param("dir", "desc")
				.header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.data[*].date", contains(tomorrow.plusDays(2).toString(), tomorrow.toString())));
	}

	@Test
	void anotherClinicSeesNoneOfOurAppointments() throws Exception {
		long a = idOf(book(ayesha, tomorrow, "10:00"));
		var other = otherClinic();
		AppUser stranger = newUser(other.getId(), "stranger@other.local", "Stranger", Role.ASSISTANT);
		mvc.perform(get("/api/v1/appointments").param("date", tomorrow.toString()).header(HttpHeaders.AUTHORIZATION, bearer(stranger)))
				.andExpect(jsonPath("$.totalData").value(0));
		mvc.perform(get("/api/v1/appointments/" + a).header(HttpHeaders.AUTHORIZATION, bearer(stranger)))
				.andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/appointments/" + a + "/cancel").header(HttpHeaders.AUTHORIZATION, bearer(stranger)))
				.andExpect(status().isNotFound());
	}

	@Test
	void aCheckedInAppointmentShowsItsQueueStateAndCountsAsCompletedOnceSeen() throws Exception {
		long a = idOf(book(ayesha, clock.today(), "23:00"));
		data.visitFromAppointment(a, ayesha, doctor.getId(), clock.today(), "completed");
		mvc.perform(get("/api/v1/appointments/" + a).header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.status").value("checked-in"))
				.andExpect(jsonPath("$.queueTokenNo").value("01"))
				.andExpect(jsonPath("$.visitStatus").value("completed"))
				.andExpect(jsonPath("$.reschedulable").value(false));
		mvc.perform(get("/api/v1/appointments/stats").param("date", clock.today().toString())
				.header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.total").value(1))
				.andExpect(jsonPath("$.completed").value(1))
				.andExpect(jsonPath("$.scheduled").value(0));
	}

	@Test
	void theDaySummaryCountsEachOutcome() throws Exception {
		long a = idOf(book(ayesha, tomorrow, "09:00"));
		long b = idOf(book(bilal, tomorrow, "09:30"));
		long c = idOf(book(data.patient("Cara", "03000000051"), tomorrow, "10:00"));
		book(data.patient("Dana", "03000000052"), tomorrow, "10:30");
		mvc.perform(post("/api/v1/appointments/" + a + "/cancel").header(HttpHeaders.AUTHORIZATION, asAssistant));
		mvc.perform(post("/api/v1/appointments/" + b + "/no-show").header(HttpHeaders.AUTHORIZATION, asAssistant));
		mvc.perform(post("/api/v1/appointments/" + c + "/arrive").header(HttpHeaders.AUTHORIZATION, asAssistant));
		mvc.perform(get("/api/v1/appointments/stats").param("date", tomorrow.toString())
				.header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.total").value(4))
				.andExpect(jsonPath("$.scheduled").value(2))
				.andExpect(jsonPath("$.cancelled").value(1))
				.andExpect(jsonPath("$.noShow").value(1))
				.andExpect(jsonPath("$.completed").value(0));
	}

	@Test
	void everythingNeedsALogin() throws Exception {
		mvc.perform(get("/api/v1/appointments")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/appointments/1/cancel")).andExpect(status().isUnauthorized());
	}
}
