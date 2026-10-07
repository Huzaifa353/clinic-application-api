package com.preclinic.backend.consultation;

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
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.preclinic.backend.ApiIntegrationTest;
import com.preclinic.backend.user.AppUser;
import com.preclinic.backend.user.Role;

class ConsultationApiTest extends ApiIntegrationTest {

	AppUser doctor;
	AppUser assistant;
	String asDoctor;
	String asAssistant;
	long ayesha;
	long bilal;
	long panadol;
	long augmentin;

	@BeforeEach
	void setUp() throws Exception {
		doctor = newUser("doc@test.local", "Dr. Test", Role.DOCTOR);
		assistant = newUser("asst@test.local", "Ayesha Assistant", Role.ASSISTANT);
		asDoctor = bearer(doctor);
		asAssistant = bearer(assistant);
		ayesha = data.patient("Ayesha Khan", "03001122334");
		bilal = data.patient("Bilal Hassan", "03214455667");
		panadol = medicine("Panadol 500mg Tablet", "Panadol", "Paracetamol", "500");
		augmentin = medicine("Augmentin 625mg Tablet", "Augmentin", "Amoxicillin", "500");
	}

	// ----- helpers -----

	private long medicine(String name, String brand, String ingredient, String strength) throws Exception {
		String body = mvc.perform(post("/api/v1/medicines").param("force", "true").header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"productName\":\"" + name + "\",\"brandName\":\"" + brand + "\",\"manufacturerName\":\"Maker\","
						+ "\"dosageForm\":\"Tablet\",\"route\":\"Oral\",\"status\":\"Active\",\"ingredients\":[{\"name\":\""
						+ ingredient + "\",\"strengthValue\":" + strength + ",\"strengthUnit\":\"mg\"}]}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	/** A patient who has been through the front desk and is now with the doctor. */
	private long withDoctor(long patientId) throws Exception {
		String body = mvc.perform(post("/api/v1/queue/intake").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"patientId\":" + patientId + ",\"consultationFee\":1500,\"vitals\":{\"bpSystolic\":150,\"bpDiastolic\":95,\"temperature\":101.2,\"pulse\":92,\"weight\":58}}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		long visit = ((Number) JsonPath.read(body, "$.id")).longValue();
		mvc.perform(patch("/api/v1/queue/" + visit + "/status").header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"consulting\"}")).andExpect(status().isOk());
		return visit;
	}

	private String fullConsultation() {
		return "{\"symptoms\":[\"Fever\",\"fever\",\"Body Ache\",\"A Brand New Symptom\"],"
				+ "\"diagnoses\":[\"Viral Fever\"],\"notes\":\"Rest and fluids.\","
				+ "\"examinationFindings\":[{\"category\":\"ENT\",\"finding\":\"Throat Congested\"},"
				+ "{\"category\":\"Other\",\"finding\":\"Custom\",\"customFinding\":\"Mild rash on arm\"}],"
				+ "\"investigationOrders\":[{\"investigationId\":\"cbc\",\"investigationName\":\"CBC\",\"isCustom\":false},"
				+ "{\"investigationName\":\"Special panel\",\"isCustom\":true}],"
				+ "\"followUp\":{\"enabled\":true,\"days\":7,\"reason\":\"Review fever\"},"
				+ "\"prescription\":[{\"medicine\":\"Panadol 500mg Tablet\",\"productId\":" + panadol
				+ ",\"frequency\":\"1-1-1\",\"duration\":\"5 Days\",\"instructions\":\"After Meals\",\"notes\":\"Only if fever\"},"
				+ "{\"medicine\":\"Warm saline gargle\",\"frequency\":\"SOS\"}]}";
	}

	private ResultActions save(long visit, String json, String query) throws Exception {
		return mvc.perform(put("/api/v1/visits/" + visit + "/consultation" + query).header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private long saveOk(long visit, String json) throws Exception {
		String body = save(visit, json, "").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	// ===== saving =====

	@Test
	void savingStoresTheWholeConsultationAndFinishesTheVisit() throws Exception {
		long visit = withDoctor(ayesha);
		LocalDate today = clock.today();
		save(visit, fullConsultation(), "")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.displayId").value("C00001"))
				.andExpect(jsonPath("$.visitId").value(visit))
				.andExpect(jsonPath("$.tokenNo").value("01"))
				.andExpect(jsonPath("$.patientName").value("Ayesha Khan"))
				.andExpect(jsonPath("$.doctorName").value("Dr. Test"))
				.andExpect(jsonPath("$.notes").value("Rest and fluids."))
				.andExpect(jsonPath("$.symptoms", contains("Fever", "Body Ache", "A Brand New Symptom")))
				.andExpect(jsonPath("$.diagnoses", contains("Viral Fever")))
				.andExpect(jsonPath("$.examinationFindings", hasSize(2)))
				.andExpect(jsonPath("$.examinationFindings[1].customFinding").value("Mild rash on arm"))
				.andExpect(jsonPath("$.investigationOrders[0].investigationId").value("cbc"))
				.andExpect(jsonPath("$.investigationOrders[0].status").value("Ordered"))
				.andExpect(jsonPath("$.investigationOrders[1].isCustom").value(true))
				.andExpect(jsonPath("$.investigationOrders[1].investigationId").doesNotExist())
				.andExpect(jsonPath("$.prescription", hasSize(2)))
				.andExpect(jsonPath("$.prescription[0].productId").value(panadol))
				.andExpect(jsonPath("$.prescription[0].instructions").value("After Meals"))
				.andExpect(jsonPath("$.prescription[1].medicine").value("Warm saline gargle"))
				.andExpect(jsonPath("$.prescription[1].productId").doesNotExist())
				.andExpect(jsonPath("$.followUp.days").value(7))
				.andExpect(jsonPath("$.followUp.dueDate").value(today.plusDays(7).toString()))
				.andExpect(jsonPath("$.followUp.state").value("Upcoming"))
				.andExpect(jsonPath("$.vitals.bpSystolic").value(150))
				.andExpect(jsonPath("$.vitals.temperature").value(101.2));

		mvc.perform(get("/api/v1/queue/" + visit).header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.status").value("completed"))
				.andExpect(jsonPath("$.completedAt").exists())
				.andExpect(jsonPath("$.hasConsultation").value(true));
	}

	@Test
	void aDraftKeepsThePatientWithTheDoctor() throws Exception {
		long visit = withDoctor(ayesha);
		save(visit, "{\"symptoms\":[\"Cough\"],\"notes\":\"draft\"}", "?complete=false").andExpect(status().isOk());
		mvc.perform(get("/api/v1/queue/" + visit).header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.status").value("consulting"))
				.andExpect(jsonPath("$.hasConsultation").value(true));
		// and finishing later completes it
		save(visit, "{\"symptoms\":[\"Cough\"],\"notes\":\"final\"}", "").andExpect(jsonPath("$.notes").value("final"));
		mvc.perform(get("/api/v1/queue/" + visit).header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.status").value("completed"));
	}

	@Test
	void savingAgainReplacesRatherThanDuplicates() throws Exception {
		long visit = withDoctor(ayesha);
		long first = saveOk(visit, fullConsultation());
		long second = saveOk(visit, "{\"symptoms\":[\"Cough\"],\"diagnoses\":[],\"notes\":\"\","
				+ "\"prescription\":[{\"medicine\":\"Panadol 500mg Tablet\",\"productId\":" + panadol + "}]}");
		assertThat(second).isEqualTo(first);
		mvc.perform(get("/api/v1/consultations/" + first).header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.displayId").value("C00001"))
				.andExpect(jsonPath("$.symptoms", contains("Cough")))
				.andExpect(jsonPath("$.diagnoses", hasSize(0)))
				.andExpect(jsonPath("$.examinationFindings", hasSize(0)))
				.andExpect(jsonPath("$.investigationOrders", hasSize(0)))
				.andExpect(jsonPath("$.prescription", hasSize(1)))
				.andExpect(jsonPath("$.followUp").doesNotExist());
		Long followUps = jdbc.sql("select count(*) from follow_up").query(Long.class).single();
		assertThat(followUps).isZero();
	}

	@Test
	void medicineUsageIsCountedOncePerPrescribingNotPerSave() throws Exception {
		long visit = withDoctor(ayesha);
		String panadolOnly = "{\"prescription\":[{\"medicine\":\"Panadol 500mg Tablet\",\"productId\":" + panadol + "}]}";
		saveOk(visit, panadolOnly);
		saveOk(visit, panadolOnly);
		saveOk(visit, panadolOnly);
		assertThat(usage(panadol)).isEqualTo(1);
		saveOk(visit, "{\"prescription\":[{\"medicine\":\"Panadol 500mg Tablet\",\"productId\":" + panadol
				+ "},{\"medicine\":\"Augmentin 625mg Tablet\",\"productId\":" + augmentin + "}]}");
		assertThat(usage(panadol)).isEqualTo(1);
		assertThat(usage(augmentin)).isEqualTo(1);
		// the next patient prescribing it again counts again
		long next = withDoctor(bilal);
		saveOk(next, panadolOnly);
		assertThat(usage(panadol)).isEqualTo(2);
		mvc.perform(get("/api/v1/medicines/frequent").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$[0].productId").value(panadol));
	}

	private int usage(long productId) {
		return jdbc.sql("select coalesce(max(usage_count), 0) from clinic_medicine where product_id = :p")
				.param("p", productId).query(Integer.class).single();
	}

	@Test
	void symptomsAndDiagnosesLinkToTheCatalogWhenTheyExistAndIgnoreCaseWhenDeduplicating() throws Exception {
		long visit = withDoctor(ayesha);
		saveOk(visit, "{\"symptoms\":[\"fever\",\"FEVER\",\"Unlisted Thing\"],\"diagnoses\":[\"viral fever\"]}");
		Long linked = jdbc.sql("select count(*) from consultation_symptom where catalog_id is not null").query(Long.class).single();
		Long total = jdbc.sql("select count(*) from consultation_symptom").query(Long.class).single();
		assertThat(linked).isEqualTo(1);
		assertThat(total).isEqualTo(2);
		assertThat(jdbc.sql("select count(*) from consultation_diagnosis where catalog_id is not null").query(Long.class).single()).isEqualTo(1);
	}

	// ===== follow-up plan =====

	@Test
	void changingTheDaysMovesTheDueDateButKeepsWhatStaffAlreadyRecorded() throws Exception {
		long visit = withDoctor(ayesha);
		long id = saveOk(visit, "{\"followUp\":{\"enabled\":true,\"days\":7}}");
		jdbc.sql("update follow_up set contact_status = 'contacted', last_reminded_on = current_date where consultation_id = :c")
				.param("c", id).update();
		saveOk(visit, "{\"followUp\":{\"enabled\":true,\"days\":14,\"reason\":\"Longer review\"}}");
		mvc.perform(get("/api/v1/consultations/" + id).header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.followUp.days").value(14))
				.andExpect(jsonPath("$.followUp.dueDate").value(clock.today().plusDays(14).toString()))
				.andExpect(jsonPath("$.followUp.reason").value("Longer review"))
				.andExpect(jsonPath("$.followUp.contactStatus").value("contacted"))
				.andExpect(jsonPath("$.followUp.lastRemindedOn").value(clock.today().toString()));
	}

	@Test
	void aRescheduledFollowUpKeepsItsNewDateWhenTheConsultationIsResaved() throws Exception {
		long visit = withDoctor(ayesha);
		long id = saveOk(visit, "{\"followUp\":{\"enabled\":true,\"days\":7}}");
		LocalDate moved = clock.today().plusDays(30);
		jdbc.sql("update follow_up set override_due_date = :d where consultation_id = :c").param("d", moved).param("c", id).update();
		saveOk(visit, "{\"followUp\":{\"enabled\":true,\"days\":7}}");
		mvc.perform(get("/api/v1/consultations/" + id).header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.followUp.dueDate").value(moved.toString()))
				.andExpect(jsonPath("$.followUp.originalDueDate").value(clock.today().plusDays(7).toString()));
	}

	@Test
	void followUpStateIsDerivedFromTheDate() throws Exception {
		assertThat(ConsultationService.followUpState(null, clock.today(), clock.today())).isEqualTo("Due");
		assertThat(ConsultationService.followUpState(null, clock.today().minusDays(1), clock.today())).isEqualTo("Overdue");
		assertThat(ConsultationService.followUpState(null, clock.today().plusDays(1), clock.today())).isEqualTo("Upcoming");
		assertThat(ConsultationService.followUpState("completed", clock.today().minusDays(9), clock.today())).isEqualTo("Completed");
		assertThat(ConsultationService.followUpState("cancelled", clock.today().plusDays(9), clock.today())).isEqualTo("Cancelled");
	}

	// ===== rules =====

	@Test
	void aPatientNotYetWithTheDoctorCannotBeRecorded() throws Exception {
		String body = mvc.perform(post("/api/v1/queue/intake").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"patientId\":" + ayesha + ",\"consultationFee\":100}"))
				.andReturn().getResponse().getContentAsString();
		long waiting = ((Number) JsonPath.read(body, "$.id")).longValue();
		save(waiting, "{\"notes\":\"x\"}", "").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("VISIT_NOT_IN_CONSULTATION"));
	}

	@Test
	void onlyTheDoctorRecordsAConsultation() throws Exception {
		long visit = withDoctor(ayesha);
		mvc.perform(put("/api/v1/visits/" + visit + "/consultation").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/visits/" + visit + "/consultation-context").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(status().isForbidden());
	}

	@Test
	void anUnknownVisitIs404() throws Exception {
		save(999999, "{}", "").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("QUEUE_ITEM_NOT_FOUND"));
	}

	@Test
	void aCompletedVisitCanBeCorrectedAgain() throws Exception {
		long visit = withDoctor(ayesha);
		saveOk(visit, "{\"notes\":\"first\"}");
		save(visit, "{\"notes\":\"corrected\"}", "").andExpect(status().isOk()).andExpect(jsonPath("$.notes").value("corrected"));
		mvc.perform(get("/api/v1/queue/" + visit).header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.status").value("completed"));
	}

	@Test
	void aPrescribedMedicineMustExistForTheClinic() throws Exception {
		long visit = withDoctor(ayesha);
		save(visit, "{\"prescription\":[{\"medicine\":\"Ghost\",\"productId\":999999}]}", "")
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UNKNOWN_MEDICINE"));
	}

	@Test
	void inputIsValidated() throws Exception {
		long visit = withDoctor(ayesha);
		for (String bad : new String[] { "{\"prescription\":[{\"medicine\":\"  \"}]}", "{\"followUp\":{\"enabled\":true,\"days\":9999}}",
				"{\"investigationOrders\":[{\"investigationName\":\"x\",\"status\":\"Maybe\"}]}",
				"{\"symptoms\":[\"\"]}" }) {
			save(visit, bad, "").andExpect(status().isBadRequest());
		}
	}

	// ===== context =====

	@Test
	void theConsultationRoomOpensWithEverythingItNeeds() throws Exception {
		jdbc.sql("update patient set allergies = '{\"Penicillin\"}' where id = :id").param("id", ayesha).update();
		long earlier = withDoctor(ayesha);
		saveOk(earlier, "{\"diagnoses\":[\"Gastroenteritis\"]}");
		long current = withDoctor(ayesha);
		long waiting = ((Number) JsonPath.read(mvc.perform(post("/api/v1/queue/intake").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"patientId\":" + bilal + ",\"consultationFee\":100}"))
				.andReturn().getResponse().getContentAsString(), "$.id")).longValue();

		mvc.perform(get("/api/v1/visits/" + current + "/consultation-context").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.visit.id").value(current))
				.andExpect(jsonPath("$.visit.vitals.bpSystolic").value(150))
				.andExpect(jsonPath("$.patient.allergies", contains("Penicillin")))
				.andExpect(jsonPath("$.consultation").doesNotExist())
				.andExpect(jsonPath("$.history", hasSize(1)))
				.andExpect(jsonPath("$.history[0].visitId").value(earlier))
				.andExpect(jsonPath("$.history[0].diagnoses", contains("Gastroenteritis")))
				.andExpect(jsonPath("$.nextWaiting.id").value(waiting));
		save(current, "{\"notes\":\"in progress\"}", "?complete=false");
		mvc.perform(get("/api/v1/visits/" + current + "/consultation-context").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.consultation.notes").value("in progress"));
	}

	// ===== lists, stats, history =====

	@Test
	void theConsultationListSearchesFiltersSortsAndPages() throws Exception {
		long v1 = withDoctor(ayesha);
		saveOk(v1, "{\"diagnoses\":[\"Viral Fever\"],\"prescription\":[{\"medicine\":\"Panadol 500mg Tablet\",\"productId\":" + panadol + "}]}");
		long v2 = withDoctor(bilal);
		saveOk(v2, "{\"diagnoses\":[\"Hypertension\"],\"prescription\":[{\"medicine\":\"Augmentin 625mg Tablet\",\"productId\":" + augmentin + "}]}");
		long v3 = withDoctor(data.patient("No Medicines", "03000000070"));
		saveOk(v3, "{\"notes\":\"advice only\"}");

		mvc.perform(get("/api/v1/consultations").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.totalData").value(3));
		mvc.perform(get("/api/v1/consultations").param("hasPrescription", "true").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.totalData").value(2));
		mvc.perform(get("/api/v1/consultations").param("q", "augmentin").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.data[*].patientName", contains("Bilal Hassan")));
		mvc.perform(get("/api/v1/consultations").param("q", "viral").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.data[*].patientName", contains("Ayesha Khan")));
		mvc.perform(get("/api/v1/consultations").param("q", "C00002").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.data[*].patientName", contains("Bilal Hassan")));
		mvc.perform(get("/api/v1/consultations").param("q", "dr. test").param("sort", "patient").param("dir", "asc")
				.header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.data[*].patientName", contains("Ayesha Khan", "Bilal Hassan", "No Medicines")));
		mvc.perform(get("/api/v1/consultations").param("sort", "patient").param("dir", "asc").param("limit", "2").param("skip", "2")
				.header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.data", hasSize(1)));
		mvc.perform(get("/api/v1/consultations").param("from", clock.today().plusDays(1).toString())
				.header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.totalData").value(0));
		mvc.perform(get("/api/v1/consultations").param("patientId", String.valueOf(ayesha)).header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.totalData").value(1));
		mvc.perform(get("/api/v1/consultations").param("q", "%").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.totalData").value(0));
		mvc.perform(get("/api/v1/consultations").param("sort", "x; drop").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(status().isBadRequest());
	}

	@Test
	void thePrescriptionCardsCountConsultationsWithMedicines() throws Exception {
		long v1 = withDoctor(ayesha);
		saveOk(v1, "{\"prescription\":[{\"medicine\":\"Panadol\",\"productId\":" + panadol + "}]}");
		long v2 = withDoctor(bilal);
		saveOk(v2, "{\"notes\":\"no medicines\"}");
		mvc.perform(get("/api/v1/consultations/stats").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.today").value(1))
				.andExpect(jsonPath("$.thisWeek").value(1))
				.andExpect(jsonPath("$.doctors").value(1));
	}

	// ===== investigation results =====

	@Test
	void anInvestigationResultCanBeRecorded() throws Exception {
		long visit = withDoctor(ayesha);
		long id = saveOk(visit, "{\"investigationOrders\":[{\"investigationId\":\"cbc\",\"investigationName\":\"CBC\"}]}");
		long order = ((Number) JsonPath.read(mvc.perform(get("/api/v1/consultations/" + id).header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andReturn().getResponse().getContentAsString(), "$.investigationOrders[0].id")).longValue();
		mvc.perform(patch("/api/v1/consultations/" + id + "/investigations/" + order).header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"Completed\",\"resultNote\":\"Hb 11.2\"}"))
				.andExpect(status().isForbidden());
		mvc.perform(patch("/api/v1/consultations/" + id + "/investigations/" + order).header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"Completed\",\"resultNote\":\"Hb 11.2\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.investigationOrders[0].status").value("Completed"))
				.andExpect(jsonPath("$.investigationOrders[0].resultNote").value("Hb 11.2"));
		mvc.perform(patch("/api/v1/consultations/" + id + "/investigations/999999").header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"Completed\"}"))
				.andExpect(status().isNotFound());
	}

	@Test
	void anotherClinicSeesNoneOfIt() throws Exception {
		long visit = withDoctor(ayesha);
		long id = saveOk(visit, fullConsultation());
		String asStranger = bearer(newUser(otherClinic().getId(), "stranger@other.local", "Stranger", Role.DOCTOR));
		mvc.perform(get("/api/v1/consultations/" + id).header(HttpHeaders.AUTHORIZATION, asStranger)).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/consultations").header(HttpHeaders.AUTHORIZATION, asStranger)).andExpect(jsonPath("$.totalData").value(0));
		mvc.perform(get("/api/v1/visits/" + visit + "/consultation-context").header(HttpHeaders.AUTHORIZATION, asStranger))
				.andExpect(status().isNotFound());
		mvc.perform(put("/api/v1/visits/" + visit + "/consultation").header(HttpHeaders.AUTHORIZATION, asStranger)
				.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
	}

	// ===== templates =====

	@Test
	void templatesAreCreatedListedAndDeleted() throws Exception {
		String body = "{\"name\":\"Common URTI\",\"medicines\":[{\"medicine\":\"Panadol 500mg Tablet\",\"productId\":" + panadol
				+ ",\"frequency\":\"1-1-1\",\"duration\":\"5 Days\",\"instructions\":\"After Food\"},"
				+ "{\"medicine\":\"Augmentin 625mg Tablet\",\"productId\":" + augmentin + ",\"frequency\":\"1-0-1\"}]}";
		String created = mvc.perform(post("/api/v1/prescription-templates").header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("Common URTI"))
				.andExpect(jsonPath("$.medicines", hasSize(2)))
				.andReturn().getResponse().getContentAsString();
		long id = ((Number) JsonPath.read(created, "$.id")).longValue();

		// the assistant reads the clinic doctor's templates
		mvc.perform(get("/api/v1/prescription-templates").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].doctorName").value("Dr. Test"))
				.andExpect(jsonPath("$[0].medicines[*].medicine", contains("Panadol 500mg Tablet", "Augmentin 625mg Tablet")))
				.andExpect(jsonPath("$[0].medicines[0].instructions").value("After Food"));

		mvc.perform(post("/api/v1/prescription-templates").header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON).content(body.replace("Common URTI", "common urti")))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TEMPLATE_NAME_TAKEN"));
		mvc.perform(post("/api/v1/prescription-templates").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
		mvc.perform(delete("/api/v1/prescription-templates/" + id).header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(status().isForbidden());

		String asStranger = bearer(newUser(otherClinic().getId(), "stranger@other.local", "Stranger", Role.DOCTOR));
		mvc.perform(delete("/api/v1/prescription-templates/" + id).header(HttpHeaders.AUTHORIZATION, asStranger))
				.andExpect(status().isNotFound());
		mvc.perform(delete("/api/v1/prescription-templates/" + id).header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/prescription-templates").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void aTemplateNeedsAtLeastOneKnownMedicine() throws Exception {
		mvc.perform(post("/api/v1/prescription-templates").header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Empty\",\"medicines\":[]}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/prescription-templates").header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Ghost\",\"medicines\":[{\"medicine\":\"x\",\"productId\":999999}]}"))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UNKNOWN_MEDICINE"));
	}

	@Test
	void everythingNeedsALogin() throws Exception {
		mvc.perform(get("/api/v1/consultations")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/prescription-templates")).andExpect(status().isUnauthorized());
	}
}
