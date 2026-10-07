package com.preclinic.backend.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

class QueueApiTest extends ApiIntegrationTest {

	AppUser doctor;
	AppUser assistant;
	String asDoctor;
	String asAssistant;
	long ayesha;
	long bilal;
	long fatima;
	long consultationService;

	@BeforeEach
	void setUp() {
		doctor = newUser("doc@test.local", "Dr. Test", Role.DOCTOR);
		assistant = newUser("asst@test.local", "Ayesha Assistant", Role.ASSISTANT);
		asDoctor = bearer(doctor);
		asAssistant = bearer(assistant);
		ayesha = data.patient("Ayesha Khan", "03001122334");
		bilal = data.patient("Bilal Hassan", "03214455667");
		fatima = data.patient("Fatima Raza", "03335566778");
		consultationService = jdbc.sql("select id from service_fee where name = 'Consultation'").query(Long.class).single();
	}

	private String intakeJson(long patientId, String extra) {
		return "{\"patientId\":" + patientId + ",\"serviceId\":" + consultationService
				+ (extra == null ? "" : "," + extra) + "}";
	}

	private String intake(long patientId) throws Exception {
		return intake(patientId, null);
	}

	private String intake(long patientId, String extra) throws Exception {
		return mvc.perform(post("/api/v1/queue/intake").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content(intakeJson(patientId, extra)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
	}

	private long id(String json) {
		return ((Number) JsonPath.read(json, "$.id")).longValue();
	}

	private void setStatus(long visitId, String status, String as) throws Exception {
		mvc.perform(patch("/api/v1/queue/" + visitId + "/status").header(HttpHeaders.AUTHORIZATION, as)
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"))
				.andExpect(status().isOk());
	}

	private org.springframework.test.web.servlet.ResultActions tryStatus(long visitId, String status, String as) throws Exception {
		return mvc.perform(patch("/api/v1/queue/" + visitId + "/status").header(HttpHeaders.AUTHORIZATION, as)
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"));
	}

	// ===== intake =====

	@Test
	void aWalkInGetsATokenAVisitAndAnInvoiceInOneStep() throws Exception {
		mvc.perform(post("/api/v1/queue/intake").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON)
				.content(intakeJson(ayesha, "\"amountPaid\":500,\"paymentMethod\":\"Cash\",\"urgent\":true,"
						+ "\"vitals\":{\"bpSystolic\":124,\"bpDiastolic\":80,\"temperature\":98.6,\"pulse\":78,\"weight\":76.5}")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.tokenNo").value("01"))
				.andExpect(jsonPath("$.status").value("waiting"))
				.andExpect(jsonPath("$.source").value("walk-in"))
				.andExpect(jsonPath("$.urgent").value(true))
				.andExpect(jsonPath("$.sortOrder").value(1))
				.andExpect(jsonPath("$.queueDate").value(clock.today().toString()))
				.andExpect(jsonPath("$.patientName").value("Ayesha Khan"))
				.andExpect(jsonPath("$.patientDisplayId").exists())
				.andExpect(jsonPath("$.vitals.bpSystolic").value(124))
				.andExpect(jsonPath("$.vitals.temperature").value(98.6))
				.andExpect(jsonPath("$.vitals.weight").value(76.5))
				.andExpect(jsonPath("$.vitals.spo2").doesNotExist())
				.andExpect(jsonPath("$.hasConsultation").value(false))
				.andExpect(jsonPath("$.billing.invoiceDisplayId").value("INV-00001"))
				.andExpect(jsonPath("$.billing.total").value(1500.0))
				.andExpect(jsonPath("$.billing.paid").value(500.0))
				.andExpect(jsonPath("$.billing.balance").value(1000.0))
				.andExpect(jsonPath("$.billing.paymentStatus").value("Partial"))
				.andExpect(jsonPath("$.billing.paymentMethod").value("Cash"));
	}

	@Test
	void theIntakeIsVisibleEverywhereItShouldBe() throws Exception {
		long visit = id(intake(ayesha, "\"amountPaid\":500"));
		mvc.perform(get("/api/v1/patients/" + ayesha).header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.totalVisits").value(1))
				.andExpect(jsonPath("$.lastVisit").value(clock.today().toString()))
				.andExpect(jsonPath("$.outstandingBalance").value(1000.0));
		mvc.perform(get("/api/v1/invoices").param("patientId", String.valueOf(ayesha)).header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.data[0].visitId").value(visit))
				.andExpect(jsonPath("$.data[0].tokenNo").value("01"));
		mvc.perform(get("/api/v1/queue").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$", hasSize(1)));
	}

	@Test
	void tokensAndOrderCountUpThroughTheDay() throws Exception {
		intake(ayesha);
		mvc.perform(post("/api/v1/queue/intake").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content(intakeJson(bilal, null)))
				.andExpect(jsonPath("$.tokenNo").value("02"))
				.andExpect(jsonPath("$.sortOrder").value(2));
	}

	@Test
	void anAppointmentIsCheckedInByTheIntake() throws Exception {
		long appt = book(ayesha, "10:00");
		mvc.perform(post("/api/v1/queue/intake").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content(intakeJson(ayesha, "\"appointmentId\":" + appt)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.source").value("appointment"))
				.andExpect(jsonPath("$.appointmentId").value(appt));
		mvc.perform(get("/api/v1/appointments/" + appt).header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.status").value("checked-in"))
				.andExpect(jsonPath("$.queueTokenNo").value("01"))
				.andExpect(jsonPath("$.visitStatus").value("waiting"));
	}

	@Test
	void todaysOpenAppointmentIsLinkedEvenWhenNotNamed() throws Exception {
		long appt = book(ayesha, "10:00");
		long other = book(bilal, "11:00");
		intake(ayesha);
		mvc.perform(get("/api/v1/appointments/" + appt).header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.status").value("checked-in"));
		mvc.perform(get("/api/v1/appointments/" + other).header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.status").value("scheduled"));
	}

	@Test
	void anAppointmentOnAnotherDayIsNotLinkedAutomatically() throws Exception {
		long future = bookOn(ayesha, clock.today().plusDays(3), "10:00");
		mvc.perform(post("/api/v1/queue/intake").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content(intakeJson(ayesha, null)))
				.andExpect(jsonPath("$.source").value("walk-in"));
		mvc.perform(get("/api/v1/appointments/" + future).header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.status").value("scheduled"));
	}

	@Test
	void anAppointmentThatCannotBeUsedIsRefusedAndNothingIsCreated() throws Exception {
		long bilalsAppt = book(bilal, "10:00");
		long future = bookOn(ayesha, clock.today().plusDays(2), "10:00");
		long cancelled = book(fatima, "11:00");
		mvc.perform(post("/api/v1/appointments/" + cancelled + "/cancel").header(HttpHeaders.AUTHORIZATION, asAssistant));

		expectIntakeError(ayesha, "\"appointmentId\":" + bilalsAppt, 422, "APPOINTMENT_PATIENT_MISMATCH");
		expectIntakeError(ayesha, "\"appointmentId\":" + future, 422, "APPOINTMENT_NOT_TODAY");
		expectIntakeError(fatima, "\"appointmentId\":" + cancelled, 422, "APPOINTMENT_NOT_AVAILABLE");
		expectIntakeError(ayesha, "\"appointmentId\":999999", 404, "APPOINTMENT_NOT_FOUND");
		mvc.perform(get("/api/v1/queue").header(HttpHeaders.AUTHORIZATION, asAssistant)).andExpect(jsonPath("$", hasSize(0)));
	}

	private void expectIntakeError(long patientId, String extra, int httpStatus, String code) throws Exception {
		mvc.perform(post("/api/v1/queue/intake").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content(intakeJson(patientId, extra)))
				.andExpect(status().is(httpStatus))
				.andExpect(jsonPath("$.code").value(code));
	}

	@Test
	void aPatientCannotBeQueuedTwiceWhileStillActive() throws Exception {
		long first = id(intake(ayesha));
		expectIntakeError(ayesha, null, 409, "PATIENT_ALREADY_IN_QUEUE");
		// a deliberate second visit is possible with force
		mvc.perform(post("/api/v1/queue/intake").param("force", "true").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content(intakeJson(ayesha, null)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.tokenNo").value("02"));
		// once the first visit is finished, queueing again is normal
		setStatus(first, "completed", asAssistant);
	}

	@Test
	void aFinishedOrCancelledVisitDoesNotBlockANewOne() throws Exception {
		long first = id(intake(ayesha));
		setStatus(first, "cancelled", asAssistant);
		mvc.perform(post("/api/v1/queue/intake").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content(intakeJson(ayesha, null)))
				.andExpect(status().isCreated());
	}

	@Test
	void intakeInputIsValidated() throws Exception {
		for (String bad : new String[] { "\"vitals\":{\"bpSystolic\":900}", "\"vitals\":{\"temperature\":40}",
				"\"vitals\":{\"pulse\":5}", "\"vitals\":{\"spo2\":120}", "\"amountPaid\":-1", "\"paymentMethod\":\"Gold\"" }) {
			mvc.perform(post("/api/v1/queue/intake").header(HttpHeaders.AUTHORIZATION, asAssistant)
					.contentType(MediaType.APPLICATION_JSON).content(intakeJson(ayesha, bad)))
					.andExpect(status().isBadRequest());
		}
		expectIntakeError(999999, null, 404, "PATIENT_NOT_FOUND");
	}

	@Test
	void moneyRulesApplyAtIntakeToo() throws Exception {
		// (separate patients: inside this rolled-back test a failed intake leaves its partial rows behind,
		// whereas the real all-or-nothing rollback is proved in QueueCommittedTest)
		expectIntakeError(ayesha, "\"amountPaid\":1501", 422, "PAYMENT_EXCEEDS_BALANCE");
		expectIntakeError(bilal, "\"discount\":2000", 422, "DISCOUNT_EXCEEDS_TOTAL");
	}

	@Test
	void onlyTheAssistantDoesIntake() throws Exception {
		mvc.perform(post("/api/v1/queue/intake").header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON).content(intakeJson(ayesha, null)))
				.andExpect(status().isForbidden());
	}

	// ===== calling patients =====

	@Test
	void callNextTakesTheFirstWaitingPatientAndNotifiesTheAssistant() throws Exception {
		long first = id(intake(ayesha));
		intake(bilal);
		mvc.perform(post("/api/v1/queue/call-next").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(first))
				.andExpect(jsonPath("$.status").value("consulting"))
				.andExpect(jsonPath("$.doctorId").value(doctor.getId()))
				.andExpect(jsonPath("$.doctorName").value("Dr. Test"))
				.andExpect(jsonPath("$.consultStartedAt").exists());

		mvc.perform(get("/api/v1/notifications").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].type").value("CONSULTATION_STARTED"))
				.andExpect(jsonPath("$[0].tokenNo").value("01"))
				.andExpect(jsonPath("$[0].patientName").value("Ayesha Khan"))
				.andExpect(jsonPath("$[0].message").value("Dr. Test has started the consultation for Ayesha Khan (Token #01). "
						+ "Please send the patient to the consultation room."))
				.andExpect(jsonPath("$[0].read").value(false));
		mvc.perform(get("/api/v1/notifications").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void theDoctorStatusShowsWhoTheyAreWith() throws Exception {
		intake(ayesha);
		mvc.perform(get("/api/v1/doctor-status").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.consultingVisitId").doesNotExist());
		mvc.perform(post("/api/v1/queue/call-next").header(HttpHeaders.AUTHORIZATION, asDoctor));
		mvc.perform(get("/api/v1/doctor-status").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.consultingToken").value("01"))
				.andExpect(jsonPath("$.consultingPatientName").value("Ayesha Khan"));
	}

	@Test
	void callNextRefusesWhileTheDoctorIsBusyAndWhenNobodyIsWaiting() throws Exception {
		mvc.perform(post("/api/v1/queue/call-next").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("QUEUE_EMPTY"));
		intake(ayesha);
		intake(bilal);
		mvc.perform(post("/api/v1/queue/call-next").header(HttpHeaders.AUTHORIZATION, asDoctor)).andExpect(status().isOk());
		mvc.perform(post("/api/v1/queue/call-next").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("QUEUE_DOCTOR_BUSY"));
	}

	@Test
	void callingASpecificPatientWhileBusyIsRefused() throws Exception {
		long first = id(intake(ayesha));
		long second = id(intake(bilal));
		setStatus(first, "consulting", asDoctor);
		tryStatus(second, "consulting", asDoctor)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("QUEUE_DOCTOR_BUSY"));
		// once the first is done the second can be called
		setStatus(first, "completed", asDoctor);
		setStatus(second, "consulting", asDoctor);
	}

	@Test
	void anAssistantCanSendThePatientToTheClinicsDoctor() throws Exception {
		long visit = id(intake(ayesha));
		tryStatus(visit, "consulting", asAssistant)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.doctorId").value(doctor.getId()));
	}

	// ===== status moves =====

	@Test
	void aPatientCanBeHeldSkippedAndReturned() throws Exception {
		long visit = id(intake(ayesha));
		setStatus(visit, "hold", asAssistant);
		setStatus(visit, "waiting", asAssistant);
		setStatus(visit, "skipped", asAssistant);
		tryStatus(visit, "waiting", asAssistant).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("waiting"));
	}

	@Test
	void settingTheSameStatusAgainIsHarmless() throws Exception {
		long visit = id(intake(ayesha));
		tryStatus(visit, "waiting", asAssistant).andExpect(status().isOk());
	}

	@Test
	void invalidMovesAreRefused() throws Exception {
		long visit = id(intake(ayesha));
		setStatus(visit, "cancelled", asAssistant);
		tryStatus(visit, "waiting", asAssistant)
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
		tryStatus(visit, "flying", asAssistant).andExpect(status().isBadRequest());
	}

	@Test
	void completedWithNoConsultationCanBeUndoneButNotOnceAConsultationExists() throws Exception {
		long visit = id(intake(ayesha));
		setStatus(visit, "completed", asAssistant);
		tryStatus(visit, "waiting", asAssistant).andExpect(status().isOk())
				.andExpect(jsonPath("$.completedAt").doesNotExist());

		long done = id(intake(bilal));
		setStatus(done, "completed", asAssistant);
		data.consultation(done, bilal, doctor.getId());
		tryStatus(done, "waiting", asAssistant)
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("VISIT_HAS_CONSULTATION"));
	}

	@Test
	void aCompletedVisitRecordsWhenItFinished() throws Exception {
		long visit = id(intake(ayesha));
		setStatus(visit, "consulting", asDoctor);
		tryStatus(visit, "completed", asDoctor)
				.andExpect(jsonPath("$.completedAt").exists())
				.andExpect(jsonPath("$.consultStartedAt").exists());
	}

	@Test
	void cancellingAVisitReleasesItsAppointmentBackToArrived() throws Exception {
		long appt = book(ayesha, "10:00");
		long visit = id(intake(ayesha, "\"appointmentId\":" + appt));
		setStatus(visit, "cancelled", asAssistant);
		mvc.perform(get("/api/v1/appointments/" + appt).header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.status").value("arrived"));
	}

	@Test
	void aVisitWithARecordedConsultationCannotBeCancelled() throws Exception {
		long visit = id(intake(ayesha));
		setStatus(visit, "consulting", asDoctor);
		data.consultation(visit, ayesha, doctor.getId());
		tryStatus(visit, "cancelled", asAssistant)
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("VISIT_HAS_CONSULTATION"));
	}

	@Test
	void aPastDaysQueueCannotBeCalled() throws Exception {
		long stale = data.visit(ayesha, null, clock.today().minusDays(1), "waiting");
		tryStatus(stale, "consulting", asDoctor)
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("QUEUE_DAY_CLOSED"));
		tryStatus(stale, "cancelled", asAssistant).andExpect(status().isOk());
	}

	@Test
	void removingFromTheQueueKeepsTheRecordAsCancelled() throws Exception {
		long visit = id(intake(ayesha));
		mvc.perform(delete("/api/v1/queue/" + visit).header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(status().isForbidden());
		mvc.perform(delete("/api/v1/queue/" + visit).header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/queue/" + visit).header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.status").value("cancelled"));
	}

	// ===== vitals, urgent, order =====

	@Test
	void vitalsAndTheUrgentFlagCanBeEditedByTheAssistant() throws Exception {
		long visit = id(intake(ayesha));
		mvc.perform(patch("/api/v1/queue/" + visit + "/vitals").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"bpSystolic\":130,\"bpDiastolic\":85,\"temperature\":99.1,\"pulse\":90,\"weight\":70,\"spo2\":97}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.vitals.bpSystolic").value(130))
				.andExpect(jsonPath("$.vitals.spo2").value(97));
		mvc.perform(patch("/api/v1/queue/" + visit + "/vitals").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"pulse\":88}"))
				.andExpect(jsonPath("$.vitals.pulse").value(88))
				.andExpect(jsonPath("$.vitals.bpSystolic").doesNotExist());
		mvc.perform(patch("/api/v1/queue/" + visit + "/urgent").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"urgent\":true}"))
				.andExpect(jsonPath("$.urgent").value(true));
	}

	@Test
	void theDoctorCannotEditFrontDeskData() throws Exception {
		long visit = id(intake(ayesha));
		mvc.perform(patch("/api/v1/queue/" + visit + "/vitals").header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON).content("{\"pulse\":88}"))
				.andExpect(status().isForbidden());
		mvc.perform(patch("/api/v1/queue/" + visit + "/urgent").header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON).content("{\"urgent\":true}"))
				.andExpect(status().isForbidden());
		mvc.perform(put("/api/v1/queue/order").header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON).content("{\"orderedIds\":[" + visit + "]}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void reorderingChangesTheDisplayOrderAndWhoIsCalledNext() throws Exception {
		long a = id(intake(ayesha));
		long b = id(intake(bilal));
		long c = id(intake(fatima));
		mvc.perform(put("/api/v1/queue/order").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"orderedIds\":[" + c + "," + a + "," + b + "]}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].id", contains((int) c, (int) a, (int) b)))
				.andExpect(jsonPath("$[*].sortOrder", contains(1, 2, 3)));
		mvc.perform(post("/api/v1/queue/call-next").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.id").value(c));
	}

	@Test
	void aPartialOrderPutsThoseFirstAndKeepsTheRest() throws Exception {
		long a = id(intake(ayesha));
		long b = id(intake(bilal));
		long c = id(intake(fatima));
		mvc.perform(put("/api/v1/queue/order").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"orderedIds\":[" + c + "]}"))
				.andExpect(jsonPath("$[*].id", contains((int) c, (int) a, (int) b)));
	}

	@Test
	void anInvalidOrderIsRefused() throws Exception {
		long a = id(intake(ayesha));
		long yesterday = data.visit(bilal, null, clock.today().minusDays(1), "completed");
		for (String body : new String[] { "{\"orderedIds\":[" + a + "," + a + "]}", "{\"orderedIds\":[999999]}",
				"{\"orderedIds\":[" + yesterday + "]}" }) {
			mvc.perform(put("/api/v1/queue/order").header(HttpHeaders.AUTHORIZATION, asAssistant)
					.contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isUnprocessableEntity())
					.andExpect(jsonPath("$.code").value("INVALID_ORDER"));
		}
		mvc.perform(put("/api/v1/queue/order").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"orderedIds\":[]}"))
				.andExpect(status().isBadRequest());
	}

	// ===== listing, history, stats =====

	@Test
	void theListFiltersByStatusSourceAndSearch() throws Exception {
		long appt = book(ayesha, "10:00");
		long a = id(intake(ayesha, "\"appointmentId\":" + appt));
		long b = id(intake(bilal));
		intake(fatima);
		setStatus(b, "hold", asAssistant);
		mvc.perform(get("/api/v1/queue").param("status", "hold").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$[*].id", contains((int) b)));
		mvc.perform(get("/api/v1/queue").param("source", "appointment").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$[*].id", contains((int) a)));
		mvc.perform(get("/api/v1/queue").param("q", "fatima").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$", hasSize(1)));
		mvc.perform(get("/api/v1/queue").param("q", "02").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$[*].id", contains((int) b)));
		mvc.perform(get("/api/v1/queue").param("q", "%").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void pastDaysAreAvailableAsHistoryAndTodayIsNotMixedIn() throws Exception {
		LocalDate yesterday = clock.today().minusDays(1);
		long old = data.visit(ayesha, doctor.getId(), yesterday, "completed");
		intake(bilal);
		mvc.perform(get("/api/v1/queue").param("date", yesterday.toString()).header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$[*].id", contains((int) old)))
				.andExpect(jsonPath("$[0].tokenNo").value("01"));
		mvc.perform(get("/api/v1/queue").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$", hasSize(1)));
	}

	@Test
	void theStatsCountEachStatusAndWhatWasCollected() throws Exception {
		long a = id(intake(ayesha, "\"amountPaid\":1500"));
		long b = id(intake(bilal, "\"amountPaid\":500"));
		long c = id(intake(fatima));
		intake(data.patient("Dana", "03000000061"));
		setStatus(a, "completed", asAssistant);
		setStatus(b, "consulting", asDoctor);
		setStatus(c, "skipped", asAssistant);
		mvc.perform(get("/api/v1/queue/stats").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.total").value(4))
				.andExpect(jsonPath("$.waiting").value(1))
				.andExpect(jsonPath("$.consulting").value(1))
				.andExpect(jsonPath("$.completed").value(1))
				.andExpect(jsonPath("$.skipped").value(1))
				.andExpect(jsonPath("$.cancelled").value(0))
				.andExpect(jsonPath("$.collected").value(2000.0));
	}

	@Test
	void anUnknownEntryIs404() throws Exception {
		mvc.perform(get("/api/v1/queue/999999").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("QUEUE_ITEM_NOT_FOUND"));
		tryStatus(999999, "hold", asAssistant).andExpect(status().isNotFound());
	}

	@Test
	void anotherClinicSeesAndChangesNothing() throws Exception {
		long visit = id(intake(ayesha));
		AppUser stranger = newUser(otherClinic().getId(), "stranger@other.local", "Stranger", Role.ASSISTANT);
		String asStranger = bearer(stranger);
		mvc.perform(get("/api/v1/queue").header(HttpHeaders.AUTHORIZATION, asStranger)).andExpect(jsonPath("$", hasSize(0)));
		mvc.perform(get("/api/v1/queue/" + visit).header(HttpHeaders.AUTHORIZATION, asStranger)).andExpect(status().isNotFound());
		tryStatus(visit, "cancelled", asStranger).andExpect(status().isNotFound());
		mvc.perform(delete("/api/v1/queue/" + visit).header(HttpHeaders.AUTHORIZATION, asStranger)).andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/queue/intake").header(HttpHeaders.AUTHORIZATION, asStranger)
				.contentType(MediaType.APPLICATION_JSON).content("{\"patientId\":" + ayesha + "}"))
				.andExpect(status().isNotFound());
	}

	// ===== notifications =====

	@Test
	void notificationsCanBeReadOneAtATimeOrAllAtOnce() throws Exception {
		long a = id(intake(ayesha));
		long b = id(intake(bilal));
		setStatus(a, "consulting", asDoctor);
		setStatus(a, "completed", asDoctor);
		setStatus(b, "consulting", asDoctor);

		mvc.perform(get("/api/v1/notifications/unread-count").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.count").value(2));
		String list = mvc.perform(get("/api/v1/notifications").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$[0].tokenNo").value("02"))
				.andReturn().getResponse().getContentAsString();
		long newest = ((Number) JsonPath.read(list, "$[0].id")).longValue();

		mvc.perform(post("/api/v1/notifications/" + newest + "/read").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/notifications/unread-count").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.count").value(1));
		mvc.perform(get("/api/v1/notifications").param("unreadOnly", "false").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$", hasSize(2)));

		mvc.perform(post("/api/v1/notifications/read-all").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/notifications/unread-count").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.count").value(0));
		mvc.perform(post("/api/v1/notifications/999999/read").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/notifications/" + newest + "/read").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(status().isNotFound());
	}

	@Test
	void onlyTodaysNotificationsAreShown() throws Exception {
		long visit = id(intake(ayesha));
		setStatus(visit, "consulting", asDoctor);
		jdbc.sql("update notification set created_at = now() - interval '2 days'").update();
		mvc.perform(get("/api/v1/notifications").param("unreadOnly", "false").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void theVisitStatusChangeIsAudited() throws Exception {
		long visit = id(intake(ayesha));
		setStatus(visit, "hold", asAssistant);
		Long entries = jdbc.sql("select count(*) from audit_log where entity = 'visit' and entity_id = :id")
				.param("id", visit).query(Long.class).single();
		assertThat(entries).isEqualTo(2); // intake + status change
	}

	@Test
	void everythingNeedsALogin() throws Exception {
		mvc.perform(get("/api/v1/queue")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/queue/call-next")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
		assertThat(new Fixtures(jdbc, defaultClinic().getId())).isNotNull();
	}

	// ----- helpers to book appointments through the API -----

	private long book(long patientId, String time) throws Exception {
		return bookOn(patientId, clock.today(), time);
	}

	private long bookOn(long patientId, LocalDate date, String time) throws Exception {
		String body = mvc.perform(post("/api/v1/appointments").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"patientId\":" + patientId + ",\"date\":\"" + date + "\",\"time\":\"" + time + "\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}
}
