package com.preclinic.backend.patient;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;
import com.preclinic.backend.ApiIntegrationTest;
import com.preclinic.backend.user.AppUser;
import com.preclinic.backend.user.Role;

class PatientApiTest extends ApiIntegrationTest {

	AppUser doctor;
	AppUser assistant;
	String asDoctor;
	String asAssistant;

	@BeforeEach
	void createUsers() {
		doctor = newUser("doc@test.local", "Dr. Test", Role.DOCTOR);
		assistant = newUser("asst@test.local", "Ayesha Assistant", Role.ASSISTANT);
		asDoctor = bearer(doctor);
		asAssistant = bearer(assistant);
	}

	private String register(String json) throws Exception {
		return mvc.perform(post("/api/v1/patients").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
	}

	private long idOf(String body) {
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	// --- register ---

	@Test
	void registeringAPatientAssignsASequentialDisplayNumber() throws Exception {
		String first = register("{\"name\":\"Ayesha Khan\",\"mobile\":\"0300-1122334\",\"gender\":\"Female\",\"age\":32}");
		String second = register("{\"name\":\"Bilal Hassan\",\"mobile\":\"03214455667\"}");
		long firstNo = ((Number) JsonPath.read(first, "$.patientNo")).longValue();
		long secondNo = ((Number) JsonPath.read(second, "$.patientNo")).longValue();
		org.assertj.core.api.Assertions.assertThat(secondNo).isEqualTo(firstNo + 1);
		mvc.perform(get("/api/v1/patients/" + idOf(first)).header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.displayId").value("P" + String.format("%05d", firstNo)))
				.andExpect(jsonPath("$.name").value("Ayesha Khan"))
				.andExpect(jsonPath("$.mobile").value("0300-1122334"))
				.andExpect(jsonPath("$.age").value(32))
				.andExpect(jsonPath("$.totalVisits").value(0))
				.andExpect(jsonPath("$.outstandingBalance").value(0))
				.andExpect(jsonPath("$.followUp").value("None"))
				.andExpect(jsonPath("$.allergies", empty()));
	}

	@Test
	void registrationRequiresANameAndAValidMobile() throws Exception {
		mvc.perform(post("/api/v1/patients").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"mobile\":\"03001234567\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		mvc.perform(post("/api/v1/patients").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\",\"mobile\":\"abc\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_MOBILE"));
	}

	@Test
	void aCnicIsFormattedOrRejected() throws Exception {
		String body = register("{\"name\":\"A\",\"mobile\":\"03001234567\",\"cnic\":\"3520212345678\"}");
		org.assertj.core.api.Assertions.assertThat((String) JsonPath.read(body, "$.cnic")).isEqualTo("35202-1234567-8");
		mvc.perform(post("/api/v1/patients").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"A\",\"mobile\":\"03001234567\",\"cnic\":\"123\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CNIC"));
	}

	@Test
	void allergiesAtRegistrationSurviveSpecialCharacters() throws Exception {
		String body = register("{\"name\":\"A\",\"mobile\":\"03001234567\","
				+ "\"allergies\":[\"Penicillin\",\"O'Brien \\\"dust\\\" \\\\ mix\"]}");
		mvc.perform(get("/api/v1/patients/" + idOf(body)).header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.allergies", contains("Penicillin", "O'Brien \"dust\" \\ mix")));
	}

	// --- mobile handling / search ---

	@Test
	void theSameNumberIsFoundNoMatterHowItIsWritten() throws Exception {
		register("{\"name\":\"Zainab Malik\",\"mobile\":\"+92 300 1234567\"}");
		for (String query : new String[] { "03001234567", "3001234567", "0300-123 4567", "+923001234567", "1234567" }) {
			mvc.perform(get("/api/v1/patients/search").param("q", query).header(HttpHeaders.AUTHORIZATION, asAssistant))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$", hasSize(1)))
					.andExpect(jsonPath("$[0].name").value("Zainab Malik"));
		}
	}

	@Test
	void aHouseholdSharingOneNumberIsReturnedTogether() throws Exception {
		register("{\"name\":\"Imran Sheikh\",\"mobile\":\"0300-9998888\"}");
		register("{\"name\":\"Nadia Sheikh\",\"mobile\":\"03009998888\"}");
		register("{\"name\":\"Someone Else\",\"mobile\":\"03451234567\"}");
		mvc.perform(get("/api/v1/patients/search").param("q", "03009998888").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$", hasSize(2)));
		mvc.perform(get("/api/v1/patients/duplicates").param("mobile", "+92 300 9998888")
				.header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$", hasSize(2)));
		mvc.perform(get("/api/v1/patients/duplicates").param("mobile", "03111111111")
				.header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void searchMatchesNameDisplayNumberAndCnic() throws Exception {
		String body = register("{\"name\":\"Fatima Raza\",\"mobile\":\"03335566778\",\"cnic\":\"35202-1111111-1\"}");
		String displayId = JsonPath.read(body, "$.displayId");
		for (String query : new String[] { "fatima", "RAZA", displayId, displayId.toLowerCase(), "352021111111" }) {
			mvc.perform(get("/api/v1/patients/search").param("q", query).header(HttpHeaders.AUTHORIZATION, asDoctor))
					.andExpect(jsonPath("$", hasSize(1)));
		}
	}

	@Test
	void searchTreatsPercentAndUnderscoreLiterally() throws Exception {
		register("{\"name\":\"Plain Name\",\"mobile\":\"03001234567\"}");
		mvc.perform(get("/api/v1/patients/search").param("q", "%").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$", hasSize(0)));
		mvc.perform(get("/api/v1/patients/search").param("q", "_lain").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void anExactMobileMatchRanksFirst() throws Exception {
		register("{\"name\":\"Aaa Partial\",\"mobile\":\"03001234999\"}");
		register("{\"name\":\"Zzz Exact\",\"mobile\":\"0300123\"}");
		register("{\"name\":\"Mmm Other\",\"mobile\":\"03005551234\"}");
		mvc.perform(get("/api/v1/patients/search").param("q", "0300123").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$[0].name").value("Zzz Exact"));
	}

	@Test
	void aBlankSearchReturnsNothing() throws Exception {
		mvc.perform(get("/api/v1/patients/search").param("q", "  ").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$", hasSize(0)));
	}

	// --- read / update ---

	@Test
	void anUnknownPatientIs404() throws Exception {
		mvc.perform(get("/api/v1/patients/999999").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PATIENT_NOT_FOUND"));
	}

	@Test
	void anotherClinicsPatientsAreInvisible() throws Exception {
		var other = otherClinic();
		long strangerId = new com.preclinic.backend.Fixtures(jdbc, other.getId()).patient("Stranger", "03007777777");
		mvc.perform(get("/api/v1/patients/" + strangerId).header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/patients/search").param("q", "Stranger").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$", hasSize(0)));
		mvc.perform(get("/api/v1/patients").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.totalData").value(0));
		mvc.perform(patch("/api/v1/patients/" + strangerId).header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Hacked\"}"))
				.andExpect(status().isNotFound());
	}

	@Test
	void demographicsCanBeEditedByTheAssistantAndEmptyStringsClear() throws Exception {
		long id = idOf(register("{\"name\":\"Old Name\",\"mobile\":\"03001234567\",\"address\":\"Old address\"}"));
		mvc.perform(patch("/api/v1/patients/" + id).header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"New Name\",\"mobile\":\"+92 321 4455667\",\"address\":\"\",\"gender\":\"Male\",\"age\":45}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("New Name"))
				.andExpect(jsonPath("$.address").doesNotExist())
				.andExpect(jsonPath("$.gender").value("Male"))
				.andExpect(jsonPath("$.age").value(45));
		// the new number is searchable in any format
		mvc.perform(get("/api/v1/patients/search").param("q", "03214455667").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$", hasSize(1)));
		// an omitted field is left alone
		mvc.perform(patch("/api/v1/patients/" + id).header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"age\":46}"))
				.andExpect(jsonPath("$.name").value("New Name"))
				.andExpect(jsonPath("$.age").value(46));
	}

	@Test
	void anInvalidMobileOnUpdateIsRejectedAndNothingChanges() throws Exception {
		long id = idOf(register("{\"name\":\"Keep\",\"mobile\":\"03001234567\"}"));
		mvc.perform(patch("/api/v1/patients/" + id).header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Changed\",\"mobile\":\"12\"}"))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/patients/" + id).header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.name").value("Keep"));
	}

	@Test
	void onlyTheDoctorEditsTheClinicalLists() throws Exception {
		long id = idOf(register("{\"name\":\"P\",\"mobile\":\"03001234567\",\"allergies\":[\"Dust\"]}"));
		String body = "{\"allergies\":[\"Penicillin\"],\"medicalHistory\":[\"Hypertension — Since 2021\"],"
				+ "\"surgicalHistory\":[],\"currentMedications\":[\"Amlodipine 5mg — 1-0-0\"]}";
		mvc.perform(put("/api/v1/patients/" + id + "/clinical").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isForbidden());
		mvc.perform(put("/api/v1/patients/" + id + "/clinical").header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.allergies", contains("Penicillin")))
				.andExpect(jsonPath("$.medicalHistory", contains("Hypertension — Since 2021")))
				.andExpect(jsonPath("$.surgicalHistory", empty()))
				.andExpect(jsonPath("$.currentMedications", contains("Amlodipine 5mg — 1-0-0")));
	}

	// --- derived figures ---

	@Test
	void visitCountLastVisitLastDoctorAndOutstandingAreDerived() throws Exception {
		long patient = data.patient("Bilal Hassan", "03214455667");
		LocalDate today = clock.today();
		long lastVisit = data.visit(patient, doctor.getId(), today.minusDays(3), "completed");
		data.visit(patient, doctor.getId(), today.minusDays(40), "completed");
		data.visit(patient, doctor.getId(), today.minusDays(1), "cancelled"); // does not count
		long invoice = data.invoice(patient, lastVisit, new BigDecimal("1500"));
		data.payment(invoice, new BigDecimal("1200"));

		mvc.perform(get("/api/v1/patients/" + patient).header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.totalVisits").value(2))
				.andExpect(jsonPath("$.lastVisit").value(today.minusDays(3).toString()))
				.andExpect(jsonPath("$.lastDoctor").value("Dr. Test"))
				.andExpect(jsonPath("$.outstandingBalance").value(300));
	}

	@Test
	void followUpStateIsDerivedFromTheNearestOpenFollowUp() throws Exception {
		LocalDate today = clock.today();
		long none = data.patient("No Follow-up", "03000000001");
		long due = data.patient("Due Today", "03000000002");
		long overdue = data.patient("Overdue", "03000000003");
		long upcoming = data.patient("Upcoming", "03000000004");
		data.followUpFor(due, doctor.getId(), today.minusDays(7), today);
		data.followUpFor(overdue, doctor.getId(), today.minusDays(20), today.minusDays(5));
		data.followUpFor(upcoming, doctor.getId(), today, today.plusDays(7));

		expectFollowUp(none, "None");
		expectFollowUp(due, "Due");
		expectFollowUp(overdue, "Overdue");
		expectFollowUp(upcoming, "Upcoming");
	}

	private void expectFollowUp(long patientId, String label) throws Exception {
		mvc.perform(get("/api/v1/patients/" + patientId).header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.followUp").value(label));
	}

	// --- list: filters, sort, paging, stats ---

	@Test
	void theListPagesAndSorts() throws Exception {
		for (String name : new String[] { "Delta", "Alpha", "Charlie", "Bravo", "Echo" }) {
			data.patient(name, "0300" + Math.abs(name.hashCode() % 10_000_000));
		}
		mvc.perform(get("/api/v1/patients").param("sort", "name").param("limit", "2").param("skip", "0")
				.header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.totalData").value(5))
				.andExpect(jsonPath("$.data[*].name", contains("Alpha", "Bravo")));
		mvc.perform(get("/api/v1/patients").param("sort", "name").param("limit", "2").param("skip", "2")
				.header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.data[*].name", contains("Charlie", "Delta")));
		mvc.perform(get("/api/v1/patients").param("sort", "name").param("dir", "desc").param("limit", "1")
				.header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.data[0].name").value("Echo"));
	}

	@Test
	void anUnknownSortIsRejectedNotInjected() throws Exception {
		mvc.perform(get("/api/v1/patients").param("sort", "name; drop table patient").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_SORT"));
	}

	@Test
	void theDefaultOrderIsMostRecentVisitFirstWithNeverSeenLast() throws Exception {
		LocalDate today = clock.today();
		long never = data.patient("Never Seen", "03000000011");
		long old = data.patient("Old Visit", "03000000012");
		long recent = data.patient("Recent Visit", "03000000013");
		data.visit(old, doctor.getId(), today.minusDays(60), "completed");
		data.visit(recent, doctor.getId(), today.minusDays(2), "completed");
		mvc.perform(get("/api/v1/patients").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.data[*].id", contains((int) recent, (int) old, (int) never)));
	}

	@Test
	void statusFilterSplitsActiveFromInactiveAtNinetyDays() throws Exception {
		LocalDate today = clock.today();
		long active = data.patient("Active One", "03000000021");
		long inactive = data.patient("Inactive One", "03000000022");
		long never = data.patient("Never One", "03000000023");
		data.visit(active, doctor.getId(), today.minusDays(90), "completed");
		data.visit(inactive, doctor.getId(), today.minusDays(91), "completed");
		mvc.perform(get("/api/v1/patients").param("status", "Active").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.data[*].id", contains((int) active)));
		mvc.perform(get("/api/v1/patients").param("status", "Inactive").param("sort", "id")
				.header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.data[*].id", contains((int) inactive, (int) never)));
	}

	@Test
	void genderFollowUpAndRegistrationDateFiltersCombine() throws Exception {
		LocalDate today = clock.today();
		long female = data.patient("Female Due", "03000000031");
		long male = data.patient("Male Due", "03000000032");
		long femaleOld = data.patient("Female Old", "03000000033");
		data.setGender(female, "Female");
		data.setGender(male, "Male");
		data.setGender(femaleOld, "Female");
		data.followUpFor(female, doctor.getId(), today.minusDays(7), today);
		data.followUpFor(male, doctor.getId(), today.minusDays(7), today);
		data.patientRegisteredDaysAgo(femaleOld, 200);

		mvc.perform(get("/api/v1/patients").param("gender", "Female").param("followUp", "Due")
				.header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.data[*].id", contains((int) female)));
		mvc.perform(get("/api/v1/patients").param("registeredTo", today.minusDays(100).toString())
				.header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.data[*].id", contains((int) femaleOld)));
		mvc.perform(get("/api/v1/patients").param("registeredFrom", today.minusDays(100).toString())
				.header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.totalData").value(2));
	}

	@Test
	void searchAndFiltersWorkTogetherInTheList() throws Exception {
		data.patient("Sheikh One", "03009998881");
		data.patient("Sheikh Two", "03009998882");
		data.patient("Other Person", "03001112223");
		mvc.perform(get("/api/v1/patients").param("q", "sheikh").param("sort", "name")
				.header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.totalData").value(2))
				.andExpect(jsonPath("$.data[*].name", contains("Sheikh One", "Sheikh Two")));
	}

	@Test
	void statsCountTotalNewActiveAndFollowUpsDue() throws Exception {
		LocalDate today = clock.today();
		long a = data.patient("Stat A", "03000000041");
		long b = data.patient("Stat B", "03000000042");
		long c = data.patient("Stat C", "03000000043");
		data.patientRegisteredDaysAgo(c, 400);
		data.visit(a, doctor.getId(), today.minusDays(5), "completed");
		data.visit(b, doctor.getId(), today.minusDays(200), "completed");
		// the fixtures' own visits must not make b and c "active", so they are dated long ago
		data.followUpFor(a, doctor.getId(), today.minusDays(7), today);
		data.followUpFor(b, doctor.getId(), today.minusDays(200), today.minusDays(10));
		data.followUpFor(c, doctor.getId(), today.minusDays(120), today.plusDays(10));

		mvc.perform(get("/api/v1/patients/stats").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(3))
				.andExpect(jsonPath("$.active").value(1))
				.andExpect(jsonPath("$.followUpsDue").value(2))
				.andExpect(jsonPath("$.newThisMonth").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
	}

	@Test
	void everythingNeedsALogin() throws Exception {
		mvc.perform(get("/api/v1/patients")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/patients/search").param("q", "a")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/patients").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"X\",\"mobile\":\"03001234567\"}")).andExpect(status().isUnauthorized());
	}
}
