package com.preclinic.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.preclinic.backend.ApiIntegrationTest;
import com.preclinic.backend.user.AppUser;
import com.preclinic.backend.user.Role;

class DocumentAndTimelineApiTest extends ApiIntegrationTest {

	static final byte[] PNG = Base64.getDecoder().decode(
			"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
	static final byte[] PDF = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n".getBytes();

	AppUser doctor;
	AppUser assistant;
	String asDoctor;
	String asAssistant;
	long patient;

	@BeforeEach
	void setUp() {
		doctor = newUser("doc@test.local", "Dr. Test", Role.DOCTOR);
		assistant = newUser("asst@test.local", "Ayesha Assistant", Role.ASSISTANT);
		asDoctor = bearer(doctor);
		asAssistant = bearer(assistant);
		patient = data.patient("Ayesha Khan", "03001122334");
	}

	private ResultActions upload(long patientId, String name, String type, byte[] bytes, String docType, String as) throws Exception {
		var request = multipart("/api/v1/patients/" + patientId + "/documents")
				.file(new MockMultipartFile("file", name, type, bytes)).header(HttpHeaders.AUTHORIZATION, as);
		if (docType != null) {
			request = request.param("docType", docType);
		}
		return mvc.perform(request);
	}

	private long uploadOk(String name, byte[] bytes, String docType) throws Exception {
		String body = upload(patient, name, "application/octet-stream", bytes, docType, asAssistant)
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	// ===== documents =====

	@Test
	void anAttachmentIsStoredListedAndDownloaded() throws Exception {
		upload(patient, "CBC_Report.pdf", "application/pdf", PDF, "Lab Report", asAssistant)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.fileName").value("CBC_Report.pdf"))
				.andExpect(jsonPath("$.docType").value("Lab Report"))
				.andExpect(jsonPath("$.contentType").value("application/pdf"))
				.andExpect(jsonPath("$.sizeBytes").value(PDF.length))
				.andExpect(jsonPath("$.uploadedBy").value("Ayesha Assistant"));
		long image = uploadOk("xray.png", PNG, "X-Ray");

		mvc.perform(get("/api/v1/patients/" + patient + "/documents").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[0].fileName").value("xray.png"));
		mvc.perform(get("/api/v1/documents/" + image + "/file").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type", "image/png"))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(content().bytes(PNG));
	}

	@Test
	void contentIsCheckedFromItsBytesAndTheTypeMustBeKnown() throws Exception {
		upload(patient, "report.pdf", "application/pdf", "just plain text".getBytes(), "Other", asAssistant)
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"));
		upload(patient, "page.html", "text/html", "<script>alert(1)</script>".getBytes(), "Other", asAssistant)
				.andExpect(status().isUnprocessableEntity());
		upload(patient, "x.png", "image/png", PNG, "Selfie", asAssistant)
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DOCUMENT_TYPE"));
		upload(patient, "empty.png", "image/png", new byte[0], "Other", asAssistant)
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FILE_REQUIRED"));
	}

	@Test
	void theTypeDefaultsToOtherAndOversizedFilesAreRefused() throws Exception {
		upload(patient, "scan.png", "image/png", PNG, null, asAssistant)
				.andExpect(status().isCreated()).andExpect(jsonPath("$.docType").value("Other"));
		byte[] big = new byte[10 * 1024 * 1024 + 1];
		System.arraycopy(PDF, 0, big, 0, PDF.length);
		upload(patient, "huge.pdf", "application/pdf", big, "Other", asAssistant)
				.andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
	}

	@Test
	void onlyTheDoctorDeletesAndTheFileGoesWithIt() throws Exception {
		long id = uploadOk("old.png", PNG, "Other");
		mvc.perform(delete("/api/v1/documents/" + id).header(HttpHeaders.AUTHORIZATION, asAssistant)).andExpect(status().isForbidden());
		mvc.perform(delete("/api/v1/documents/" + id).header(HttpHeaders.AUTHORIZATION, asDoctor)).andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/documents/" + id + "/file").header(HttpHeaders.AUTHORIZATION, asDoctor)).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/patients/" + patient + "/documents").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$", hasSize(0)));
		assertThat(jdbc.sql("select count(*) from file_asset where file_name = 'old.png'").query(Long.class).single()).isZero();
	}

	@Test
	void unknownPatientsAndForeignClinicsAreRefused() throws Exception {
		upload(999999, "x.png", "image/png", PNG, "Other", asAssistant).andExpect(status().isNotFound());
		long id = uploadOk("mine.png", PNG, "Other");
		String asStranger = bearer(newUser(otherClinic().getId(), "stranger@other.local", "Stranger", Role.DOCTOR));
		mvc.perform(get("/api/v1/documents/" + id + "/file").header(HttpHeaders.AUTHORIZATION, asStranger)).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/patients/" + patient + "/documents").header(HttpHeaders.AUTHORIZATION, asStranger)).andExpect(status().isNotFound());
		mvc.perform(delete("/api/v1/documents/" + id).header(HttpHeaders.AUTHORIZATION, asStranger)).andExpect(status().isNotFound());
		upload(patient, "x.png", "image/png", PNG, "Other", asStranger).andExpect(status().isNotFound());
	}

	// ===== timeline =====

	/** A visit with a consultation + payment + follow-up outcome + two documents, spread over time. */
	private void buildHistory() throws Exception {
		LocalDate today = clock.today();
		long visit = data.visit(patient, doctor.getId(), today.minusDays(10), "completed");
		long consultation = data.consultation(visit, patient, doctor.getId());
		jdbc.sql("insert into consultation_diagnosis (consultation_id, position, name) values (:c, 0, 'Viral Fever'), (:c, 1, 'Dehydration')")
				.param("c", consultation).update();
		jdbc.sql("insert into prescription_item (consultation_id, position, medicine_name) values (:c, 0, 'Panadol')")
				.param("c", consultation).update();
		long followUp = data.followUp(consultation, patient, today.minusDays(3));
		jdbc.sql("update follow_up set status = 'completed', resolved_at = now() - interval '2 days' where id = :id")
				.param("id", followUp).update();
		long invoice = data.invoice(patient, visit, new BigDecimal("1500"));
		data.paymentDaysAgo(invoice, new BigDecimal("1000"), 9);
		jdbc.sql("update consultation set created_at = now() - interval '10 days'").update();
		upload(patient, "lab.pdf", "application/pdf", PDF, "Lab Report", asAssistant).andExpect(status().isCreated());
		upload(patient, "referral.pdf", "application/pdf", PDF, "Referral", asDoctor).andExpect(status().isCreated());
	}

	@Test
	void theTimelineMergesEveryKindOfEventNewestFirst() throws Exception {
		buildHistory();
		mvc.perform(get("/api/v1/patients/" + patient + "/timeline").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(5)))
				.andExpect(jsonPath("$[*].type", contains("referral", "document", "followup-resolved", "payment", "consultation")));
	}

	@Test
	void eachEventCarriesWhatTheCardNeeds() throws Exception {
		buildHistory();
		mvc.perform(get("/api/v1/patients/" + patient + "/timeline").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$[?(@.type=='consultation')].consultationDisplayId").value("C00001"))
				.andExpect(jsonPath("$[?(@.type=='consultation')].doctorName").value("Dr. Test"))
				.andExpect(jsonPath("$[4].type").value("consultation"))
				.andExpect(jsonPath("$[4].diagnoses", contains("Viral Fever", "Dehydration")))
				.andExpect(jsonPath("$[?(@.type=='consultation')].medicines").value(1))
				.andExpect(jsonPath("$[?(@.type=='consultation')].followUpPlanned").value(true))
				.andExpect(jsonPath("$[?(@.type=='payment')].amount").value(1000.0))
				.andExpect(jsonPath("$[?(@.type=='payment')].method").value("Cash"))
				.andExpect(jsonPath("$[?(@.type=='payment')].receiptDisplayId").value("REC000001"))
				.andExpect(jsonPath("$[?(@.type=='payment')].invoiceDisplayId").value("INV-00001"))
				.andExpect(jsonPath("$[?(@.type=='followup-resolved')].followUpStatus").value("completed"))
				.andExpect(jsonPath("$[?(@.type=='referral')].fileName").value("referral.pdf"))
				.andExpect(jsonPath("$[?(@.type=='document')].documentType").value("Lab Report"))
				.andExpect(jsonPath("$[0].date").value(clock.today().toString()));
	}

	@Test
	void theProfileChipsFilterTheFeed() throws Exception {
		buildHistory();
		expectTypes("consultations", "consultation");
		expectTypes("payments", "payment");
		expectTypes("referrals", "referral");
		expectTypes("documents", "document");
		// follow-ups = resolved follow-ups + consultations that planned one
		expectTypes("followups", "followup-resolved", "consultation");
		expectTypes("all", "referral", "document", "followup-resolved", "payment", "consultation");
	}

	private void expectTypes(String filter, String... types) throws Exception {
		mvc.perform(get("/api/v1/patients/" + patient + "/timeline").param("filter", filter).header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$[*].type", contains((Object[]) types)));
	}

	@Test
	void theTimelinePages() throws Exception {
		buildHistory();
		mvc.perform(get("/api/v1/patients/" + patient + "/timeline").param("limit", "2").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$[*].type", contains("referral", "document")));
		mvc.perform(get("/api/v1/patients/" + patient + "/timeline").param("limit", "2").param("skip", "4").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$[*].type", contains("consultation")));
	}

	@Test
	void onlyThisPatientsEventsAppear() throws Exception {
		buildHistory();
		long other = data.patient("Someone Else", "03000000301");
		long visit = data.visit(other, doctor.getId(), clock.today().minusDays(1), "completed");
		data.consultation(visit, other, doctor.getId());
		mvc.perform(get("/api/v1/patients/" + other + "/timeline").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$", hasSize(1)));
		mvc.perform(get("/api/v1/patients/" + patient + "/timeline").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$", hasSize(5)));
	}

	@Test
	void anEmptyHistoryAndBadInputAreHandled() throws Exception {
		mvc.perform(get("/api/v1/patients/" + patient + "/timeline").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
		mvc.perform(get("/api/v1/patients/" + patient + "/timeline").param("filter", "everything").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FILTER"));
		mvc.perform(get("/api/v1/patients/999999/timeline").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(status().isNotFound());
		String asStranger = bearer(newUser(otherClinic().getId(), "stranger@other.local", "Stranger", Role.DOCTOR));
		mvc.perform(get("/api/v1/patients/" + patient + "/timeline").header(HttpHeaders.AUTHORIZATION, asStranger))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/patients/" + patient + "/timeline")).andExpect(status().isUnauthorized());
	}
}
