package com.preclinic.backend.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.preclinic.backend.Fixtures;
import com.preclinic.backend.user.AppUser;
import com.preclinic.backend.user.Role;

class BillingApiTest extends ApiIntegrationTest {

	AppUser doctor;
	AppUser assistant;
	String asDoctor;
	String asAssistant;
	long patient;
	long consultationService;

	@BeforeEach
	void setUp() {
		doctor = newUser("doc@test.local", "Dr. Test", Role.DOCTOR);
		assistant = newUser("asst@test.local", "Ayesha Assistant", Role.ASSISTANT);
		asDoctor = bearer(doctor);
		asAssistant = bearer(assistant);
		patient = data.patient("Bilal Hassan", "03214455667");
		consultationService = jdbc.sql("select id from service_fee where name = 'Consultation'")
				.query(Long.class).single();
	}

	private String createInvoice(String json) throws Exception {
		return mvc.perform(post("/api/v1/invoices").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
	}

	private long invoice(String fee, String paid) throws Exception {
		String json = "{\"patientId\":" + patient + ",\"consultationFee\":" + fee
				+ (paid == null ? "" : ",\"amountPaid\":" + paid) + "}";
		return ((Number) JsonPath.read(createInvoice(json), "$.id")).longValue();
	}

	private String pay(long invoiceId, String amount, String method) throws Exception {
		return mvc.perform(post("/api/v1/invoices/" + invoiceId + "/payments")
				.header(HttpHeaders.AUTHORIZATION, asAssistant).contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":" + amount + ",\"method\":\"" + method + "\",\"reference\":\"TXN-1\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
	}

	// --- creating invoices ---

	@Test
	void aServiceSuppliesTheFeeAndTheDescription() throws Exception {
		mvc.perform(post("/api/v1/invoices").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"patientId\":" + patient + ",\"serviceId\":" + consultationService + "}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.displayId").value("INV-00001"))
				.andExpect(jsonPath("$.description").value("Consultation"))
				.andExpect(jsonPath("$.consultationFee").value(1500.0))
				.andExpect(jsonPath("$.total").value(1500.0))
				.andExpect(jsonPath("$.paid").value(0))
				.andExpect(jsonPath("$.balance").value(1500.0))
				.andExpect(jsonPath("$.paymentStatus").value("Unpaid"))
				.andExpect(jsonPath("$.doctorName").value("Dr. Test"))
				.andExpect(jsonPath("$.patientName").value("Bilal Hassan"))
				.andExpect(jsonPath("$.payments", hasSize(0)));
	}

	@Test
	void totalIsFeePlusChargesMinusDiscountAndAFirstPaymentIsRecorded() throws Exception {
		mvc.perform(post("/api/v1/invoices").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"patientId\":" + patient + ",\"consultationFee\":1500,\"additionalCharges\":300,"
						+ "\"discount\":200,\"amountPaid\":1000,\"paymentMethod\":\"EasyPaisa\",\"description\":\"Visit + dressing\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.total").value(1600.0))
				.andExpect(jsonPath("$.paid").value(1000.0))
				.andExpect(jsonPath("$.balance").value(600.0))
				.andExpect(jsonPath("$.paymentStatus").value("Partial"))
				.andExpect(jsonPath("$.description").value("Visit + dressing"))
				.andExpect(jsonPath("$.payments", hasSize(1)))
				.andExpect(jsonPath("$.payments[0].method").value("EasyPaisa"))
				.andExpect(jsonPath("$.payments[0].receiptDisplayId").value("REC000001"))
				.andExpect(jsonPath("$.payments[0].receivedBy").value("Ayesha Assistant"));
	}

	@Test
	void payingTheWholeAmountAtCreationMakesItPaid() throws Exception {
		mvc.perform(post("/api/v1/invoices").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"patientId\":" + patient + ",\"consultationFee\":1500,\"amountPaid\":1500}"))
				.andExpect(jsonPath("$.paymentStatus").value("Paid"))
				.andExpect(jsonPath("$.balance").value(0));
	}

	@Test
	void aFreeVisitIsSettledNotUnpaid() throws Exception {
		mvc.perform(post("/api/v1/invoices").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"patientId\":" + patient + ",\"consultationFee\":500,\"discount\":500}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.total").value(0))
				.andExpect(jsonPath("$.paymentStatus").value("Paid"));
	}

	@Test
	void impossibleAmountsAreRefused() throws Exception {
		mvc.perform(post("/api/v1/invoices").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"patientId\":" + patient + ",\"consultationFee\":500,\"discount\":600}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("DISCOUNT_EXCEEDS_TOTAL"));
		mvc.perform(post("/api/v1/invoices").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"patientId\":" + patient + ",\"consultationFee\":500,\"amountPaid\":501}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("PAYMENT_EXCEEDS_BALANCE"));
		mvc.perform(post("/api/v1/invoices").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"patientId\":" + patient + ",\"consultationFee\":-5}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void unknownPatientServiceOrMismatchedVisitIsRefused() throws Exception {
		mvc.perform(post("/api/v1/invoices").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"patientId\":999999,\"consultationFee\":100}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PATIENT_NOT_FOUND"));
		mvc.perform(post("/api/v1/invoices").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"patientId\":" + patient + ",\"serviceId\":999999}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SERVICE_NOT_FOUND"));
		long other = data.patient("Someone Else", "03000000099");
		long othersVisit = data.visit(other, doctor.getId(), clock.today(), "waiting");
		mvc.perform(post("/api/v1/invoices").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"patientId\":" + patient + ",\"visitId\":" + othersVisit + ",\"consultationFee\":100}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("VISIT_PATIENT_MISMATCH"));
	}

	// --- payments ---

	@Test
	void partialPaymentsAddUpToPaidAndEachGetsItsOwnReceipt() throws Exception {
		long id = invoice("1500", null);
		String first = pay(id, "500", "Cash");
		assertThat((String) JsonPath.read(first, "$.payment.receiptDisplayId")).isEqualTo("REC000001");
		assertThat(((Number) JsonPath.read(first, "$.invoice.balance")).doubleValue()).isEqualTo(1000.0);
		assertThat((String) JsonPath.read(first, "$.invoice.paymentStatus")).isEqualTo("Partial");

		String second = pay(id, "1000", "JazzCash");
		assertThat((String) JsonPath.read(second, "$.payment.receiptDisplayId")).isEqualTo("REC000002");
		assertThat((String) JsonPath.read(second, "$.invoice.paymentStatus")).isEqualTo("Paid");

		mvc.perform(get("/api/v1/invoices/" + id).header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.paid").value(1500.0))
				.andExpect(jsonPath("$.payments", hasSize(2)))
				.andExpect(jsonPath("$.payments[*].method", contains("Cash", "JazzCash")))
				.andExpect(jsonPath("$.payments[0].reference").value("TXN-1"));
	}

	@Test
	void aDoctorMayRecordAPaymentToo() throws Exception {
		long id = invoice("1000", null);
		mvc.perform(post("/api/v1/invoices/" + id + "/payments").header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000,\"method\":\"Card\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.payment.receivedBy").value("Dr. Test"));
	}

	@Test
	void paymentsCannotExceedTheBalanceOrHitAnInvalidState() throws Exception {
		long id = invoice("1000", "400");
		mvc.perform(post("/api/v1/invoices/" + id + "/payments").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"amount\":601,\"method\":\"Cash\"}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("PAYMENT_EXCEEDS_BALANCE"));
		pay(id, "600", "Cash");
		mvc.perform(post("/api/v1/invoices/" + id + "/payments").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1,\"method\":\"Cash\"}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("INVOICE_SETTLED"));
	}

	@Test
	void paymentInputIsValidated() throws Exception {
		long id = invoice("1000", null);
		for (String body : new String[] { "{\"amount\":0,\"method\":\"Cash\"}", "{\"amount\":-5,\"method\":\"Cash\"}",
				"{\"amount\":10,\"method\":\"Bitcoin\"}", "{\"method\":\"Cash\"}", "{\"amount\":10}" }) {
			mvc.perform(post("/api/v1/invoices/" + id + "/payments").header(HttpHeaders.AUTHORIZATION, asAssistant)
					.contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isBadRequest());
		}
	}

	@Test
	void anUnknownInvoiceIs404() throws Exception {
		mvc.perform(post("/api/v1/invoices/999999/payments").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10,\"method\":\"Cash\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("INVOICE_NOT_FOUND"));
		mvc.perform(get("/api/v1/invoices/999999").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(status().isNotFound());
	}

	// --- voiding ---

	@Test
	void onlyTheDoctorMayVoidAndItIsAudited() throws Exception {
		long id = invoice("1000", "300");
		mvc.perform(post("/api/v1/invoices/" + id + "/void").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"mistake\"}"))
				.andExpect(status().isForbidden());

		mvc.perform(post("/api/v1/invoices/" + id + "/void").header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Entered by mistake\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paymentStatus").value("Void"))
				.andExpect(jsonPath("$.voided").value(true))
				.andExpect(jsonPath("$.voidReason").value("Entered by mistake"))
				.andExpect(jsonPath("$.payments", hasSize(1)));

		Long audited = jdbc.sql("select count(*) from audit_log where action = 'invoice.void' and entity_id = :id")
				.param("id", id).query(Long.class).single();
		assertThat(audited).isEqualTo(1);
	}

	@Test
	void aVoidedInvoiceTakesNoPaymentsAndCannotBeVoidedTwice() throws Exception {
		long id = invoice("1000", null);
		mvc.perform(post("/api/v1/invoices/" + id + "/void").header(HttpHeaders.AUTHORIZATION, asDoctor));
		mvc.perform(post("/api/v1/invoices/" + id + "/payments").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10,\"method\":\"Cash\"}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("INVOICE_VOID"));
		mvc.perform(post("/api/v1/invoices/" + id + "/void").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("INVOICE_ALREADY_VOID"));
	}

	// --- list ---

	@Test
	void theListFiltersByStatusMethodAndSearch() throws Exception {
		long unpaid = invoice("1000", null);
		long partial = invoice("1000", "400");
		long paid = invoice("1000", "1000");
		pay(unpaid, "100", "JazzCash");
		mvc.perform(post("/api/v1/invoices/" + paid + "/void").header(HttpHeaders.AUTHORIZATION, asDoctor));

		mvc.perform(get("/api/v1/invoices").param("status", "Partial").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.totalData").value(2))
				.andExpect(jsonPath("$.data[*].id", org.hamcrest.Matchers.hasItems((int) unpaid, (int) partial)));
		mvc.perform(get("/api/v1/invoices").param("status", "Cancelled").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.data[*].id", contains((int) paid)));
		mvc.perform(get("/api/v1/invoices").param("method", "JazzCash").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.data[*].id", contains((int) unpaid)));
		mvc.perform(get("/api/v1/invoices").param("q", "REC000003").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.data[*].id", contains((int) unpaid)));
		mvc.perform(get("/api/v1/invoices").param("q", "inv-00002").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.data[*].id", contains((int) partial)));
		mvc.perform(get("/api/v1/invoices").param("q", "txn-1").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.data[*].id", contains((int) unpaid)));
		mvc.perform(get("/api/v1/invoices").param("q", "bilal").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.totalData").value(3));
		mvc.perform(get("/api/v1/invoices").param("q", "%").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.totalData").value(0));
	}

	@Test
	void theListPagesNewestFirstAndCanSort() throws Exception {
		long a = invoice("100", null);
		long b = invoice("300", null);
		long c = invoice("200", null);
		mvc.perform(get("/api/v1/invoices").param("limit", "2").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.totalData").value(3))
				.andExpect(jsonPath("$.data[*].id", contains((int) c, (int) b)));
		mvc.perform(get("/api/v1/invoices").param("sort", "total").param("dir", "asc")
				.header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.data[*].id", contains((int) a, (int) c, (int) b)));
		mvc.perform(get("/api/v1/invoices").param("sort", "total; drop table invoice").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(status().isBadRequest());
	}

	@Test
	void theListFiltersByDateUsingTheClinicDay() throws Exception {
		long recent = invoice("100", null);
		long old = invoice("200", null);
		jdbc.sql("update invoice set issued_at = now() - interval '10 days' where id = :id").param("id", old).update();
		LocalDate today = clock.today();
		mvc.perform(get("/api/v1/invoices").param("from", today.toString()).param("to", today.toString())
				.header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.data[*].id", contains((int) recent)));
		mvc.perform(get("/api/v1/invoices").param("to", today.minusDays(5).toString())
				.header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.data[*].id", contains((int) old)));
	}

	@Test
	void aPatientsInvoicesAreFilteredByPatientId() throws Exception {
		invoice("100", null);
		long other = data.patient("Other", "03000000077");
		jdbc.sql("insert into invoice (clinic_id, invoice_no, patient_id, consultation_fee, total) values (:c, 99, :p, 50, 50)")
				.param("c", defaultClinic().getId()).param("p", other).update();
		mvc.perform(get("/api/v1/invoices").param("patientId", String.valueOf(patient))
				.header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.totalData").value(1));
	}

	// --- summary & cross-module figures ---

	@Test
	void theSummaryCountsCollectionBilledPendingAndOutstanding() throws Exception {
		long a = invoice("1000", "1000");          // paid in cash
		long b = invoice("2000", "500");           // partial
		long c = invoice("300", null);             // unpaid
		pay(b, "250", "JazzCash");
		long voided = invoice("999", "999");
		mvc.perform(post("/api/v1/invoices/" + voided + "/void").header(HttpHeaders.AUTHORIZATION, asDoctor));

		mvc.perform(get("/api/v1/invoices/summary").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.collection").value(1750.0))
				.andExpect(jsonPath("$.billed").value(3300.0))
				.andExpect(jsonPath("$.outstanding").value(1550.0))
				.andExpect(jsonPath("$.invoiceCount").value(3))
				.andExpect(jsonPath("$.pendingCount").value(2))
				.andExpect(jsonPath("$.byMethod[?(@.method=='Cash')].amount").value(1500.0))
				.andExpect(jsonPath("$.byMethod[?(@.method=='JazzCash')].amount").value(250.0));
		assertThat(a + b + c).isPositive();
	}

	@Test
	void thePeriodLimitsCollectionButNotOutstanding() throws Exception {
		long old = invoice("1000", "200");
		jdbc.sql("update invoice set issued_at = now() - interval '30 days' where id = :id").param("id", old).update();
		jdbc.sql("alter table payment disable trigger trg_payment_immutable").update();
		jdbc.sql("update payment set paid_at = now() - interval '30 days' where invoice_id = :id").param("id", old).update();
		jdbc.sql("alter table payment enable trigger trg_payment_immutable").update();
		invoice("500", "500");

		mvc.perform(get("/api/v1/invoices/summary").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.collection").value(500.0))
				.andExpect(jsonPath("$.invoiceCount").value(1))
				.andExpect(jsonPath("$.outstanding").value(800.0));
	}

	@Test
	void thePatientsOutstandingBalanceFollowsTheirInvoices() throws Exception {
		long id = invoice("1000", "250");
		mvc.perform(get("/api/v1/patients/" + patient).header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.outstandingBalance").value(750.0));
		pay(id, "750", "Cash");
		mvc.perform(get("/api/v1/patients/" + patient).header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.outstandingBalance").value(0));
	}

	@Test
	void anotherClinicCannotSeeOrTouchOurInvoices() throws Exception {
		long id = invoice("1000", null);
		AppUser stranger = newUser(otherClinic().getId(), "stranger@other.local", "Stranger", Role.DOCTOR);
		String asStranger = bearer(stranger);
		mvc.perform(get("/api/v1/invoices").header(HttpHeaders.AUTHORIZATION, asStranger))
				.andExpect(jsonPath("$.totalData").value(0));
		mvc.perform(get("/api/v1/invoices/" + id).header(HttpHeaders.AUTHORIZATION, asStranger))
				.andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/invoices/" + id + "/payments").header(HttpHeaders.AUTHORIZATION, asStranger)
				.contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10,\"method\":\"Cash\"}"))
				.andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/invoices/" + id + "/void").header(HttpHeaders.AUTHORIZATION, asStranger))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/invoices/summary").header(HttpHeaders.AUTHORIZATION, asStranger))
				.andExpect(jsonPath("$.outstanding").value(0));
	}

	@Test
	void everythingNeedsALogin() throws Exception {
		mvc.perform(get("/api/v1/invoices")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/invoices/1/payments").contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":1,\"method\":\"Cash\"}")).andExpect(status().isUnauthorized());
		assertThat(new Fixtures(jdbc, defaultClinic().getId())).isNotNull();
		assertThat(BigDecimal.ONE).isNotNull();
	}
}
