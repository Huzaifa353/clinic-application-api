package com.preclinic.backend.report;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

import com.preclinic.backend.ApiIntegrationTest;
import com.preclinic.backend.user.AppUser;
import com.preclinic.backend.user.Role;

/** A hand-built clinic with known numbers, so every figure on the report can be checked exactly. */
class ReportApiTest extends ApiIntegrationTest {

	AppUser docA;
	AppUser docB;
	AppUser assistant;
	String asAssistant;
	String asDocA;
	LocalDate today;
	long p1; // registered long ago
	long p2; // registered today
	long p3; // registered long ago, never seen in range

	@BeforeEach
	void build() {
		docA = newUser("a@test.local", "Dr. A", Role.DOCTOR);
		docB = newUser("b@test.local", "Dr. B", Role.DOCTOR);
		assistant = newUser("asst@test.local", "Assistant", Role.ASSISTANT);
		asAssistant = bearer(assistant);
		asDocA = bearer(docA);
		today = clock.today();

		p1 = data.patient("Patient One", "03000000101");
		p2 = data.patient("Patient Two", "03000000102");
		p3 = data.patient("Patient Three", "03000000103");
		data.patientRegisteredDaysAgo(p1, 90);
		data.patientRegisteredDaysAgo(p3, 90);

		// consultations: P1 with A (3 days ago, follow-up planned), P2 with A (today), P1 with B (2 days ago)
		long v1 = data.visit(p1, docA.getId(), today.minusDays(3), "completed");
		long c1 = data.consultation(v1, p1, docA.getId());
		data.followUp(c1, p1, today.plusDays(4));
		long v2 = data.visit(p2, docA.getId(), today, "completed");
		data.consultation(v2, p2, docA.getId());
		long v3 = data.visit(p1, docB.getId(), today.minusDays(2), "completed");
		data.consultation(v3, p1, docB.getId());
		data.visit(p3, null, today.minusDays(1), "cancelled");
		data.visit(p3, null, today.minusDays(1), "skipped");

		// money: A billed 1000 (paid 1000 cash) + 2000 (paid 500 JazzCash); B billed 1500 unpaid; one voided; one old
		long inv1 = invoice(p1, docA, "1000", 0);
		pay(inv1, "1000", "Cash");
		long inv2 = invoice(p2, docA, "2000", 0);
		pay(inv2, "500", "JazzCash");
		invoice(p1, docB, "1500", 0);
		long voided = invoice(p2, docA, "999", 0);
		pay(voided, "999", "Cash");
		jdbc.sql("update invoice set voided = true, voided_at = now() where id = :id").param("id", voided).update();
		long old = invoice(p3, docA, "400", 40);

		// appointments (all for docA): completed, cancelled, no-show, plain past, future, and a rescheduled one
		long done = appointment(p1, docA, today.minusDays(3), "checked-in");
		data.visitFromAppointment(done, p1, docA.getId(), today.minusDays(3), "completed");
		appointment(p2, docA, today.minusDays(4), "cancelled");
		appointment(p3, docA, today.minusDays(5), "no-show");
		appointment(p3, docA, today.minusDays(6), "scheduled");
		appointment(p2, docA, today.plusDays(1), "scheduled");
		appointment(p1, docA, today.minusDays(2), "rescheduled");

		// follow-ups beyond the one above: one overdue, one completed, one cancelled
		data.followUpFor(p2, docA.getId(), today.minusDays(20), today.minusDays(6));
		long closed1 = data.followUpFor(p3, docA.getId(), today.minusDays(30), today.minusDays(10));
		long closed2 = data.followUpFor(p3, docB.getId(), today.minusDays(35), today.minusDays(12));
		jdbc.sql("update follow_up set status = 'completed', resolved_at = now() where id = :id").param("id", closed1).update();
		jdbc.sql("update follow_up set status = 'cancelled', resolved_at = now() where id = :id").param("id", closed2).update();
		jdbc.sql("update invoice set issued_at = now() - make_interval(days => 40) where id = :id").param("id", old).update();
	}

	private long invoice(long patient, AppUser doctor, String fee, int ignored) {
		long id = data.invoice(patient, null, new BigDecimal(fee));
		jdbc.sql("update invoice set doctor_id = :d where id = :id").param("d", doctor.getId()).param("id", id).update();
		return id;
	}

	private void pay(long invoice, String amount, String method) {
		jdbc.sql("""
				insert into payment (clinic_id, receipt_no, invoice_id, amount, method)
				values (:c, (select coalesce(max(receipt_no), 0) + 1 from payment where clinic_id = :c), :i, :a, :m)
				""").param("c", defaultClinic().getId()).param("i", invoice).param("a", new BigDecimal(amount))
				.param("m", method).update();
	}

	private long appointment(long patient, AppUser doctor, LocalDate date, String status) {
		return jdbc.sql("""
				insert into appointment (clinic_id, appointment_no, patient_id, doctor_id, appt_date, appt_time, status)
				values (:c, (select coalesce(max(appointment_no), 0) + 1 from appointment where clinic_id = :c), :p, :d, :date,
				        make_time(9, (select (count(*) %% 50)::int from appointment), 0), :s)
				returning id
				""".replace("%%", "%")).param("c", defaultClinic().getId()).param("p", patient).param("d", doctor.getId())
				.param("date", date).param("s", status).query(Long.class).single();
	}

	private ResultActions report(String as, String... pairs) throws Exception {
		var request = get("/api/v1/reports/summary").header(HttpHeaders.AUTHORIZATION, as)
				.param("from", today.minusDays(10).toString()).param("to", today.plusDays(2).toString());
		for (int i = 0; i < pairs.length; i += 2) {
			request = request.param(pairs[i], pairs[i + 1]);
		}
		return mvc.perform(request);
	}

	// ===== the whole clinic =====

	@Test
	void patientsSeenNewAndReturning() throws Exception {
		report(asAssistant).andExpect(status().isOk())
				.andExpect(jsonPath("$.patients.seen").value(2))
				.andExpect(jsonPath("$.patients.newCount").value(1))
				.andExpect(jsonPath("$.patients.returning").value(1))
				.andExpect(jsonPath("$.patients.registered").value(1))
				.andExpect(jsonPath("$.patients.trend.labels", hasSize(13)))
				.andExpect(jsonPath("$.patients.trend.values", hasSize(13)));
	}

	@Test
	void appointmentsDueCompletedCancelledNoShowAndUpcoming() throws Exception {
		report(asAssistant)
				.andExpect(jsonPath("$.appointments.due").value(4))
				.andExpect(jsonPath("$.appointments.completed").value(1))
				.andExpect(jsonPath("$.appointments.cancelled").value(1))
				.andExpect(jsonPath("$.appointments.noShow").value(1))
				.andExpect(jsonPath("$.appointments.upcoming").value(1))
				.andExpect(jsonPath("$.appointments.completionRate").value(25.0))
				.andExpect(jsonPath("$.appointments.noShowRate").value(25.0));
	}

	@Test
	void theQueueSummary() throws Exception {
		report(asAssistant)
				.andExpect(jsonPath("$.queue.tokensIssued").value(6))
				.andExpect(jsonPath("$.queue.patientsServed").value(4))
				.andExpect(jsonPath("$.queue.cancelledOrSkipped").value(2));
	}

	@Test
	void moneyBilledCollectedOutstandingAndByMethod() throws Exception {
		report(asAssistant)
				.andExpect(jsonPath("$.financial.billed").value(4500))
				.andExpect(jsonPath("$.financial.collected").value(1500))
				// outstanding is as of today and ignores the range: 1500 (inv2) + 1500 (B) + 400 (old)
				.andExpect(jsonPath("$.financial.outstanding").value(3400))
				.andExpect(jsonPath("$.financial.byMethod[?(@.method=='Cash')].amount").value(1000))
				.andExpect(jsonPath("$.financial.byMethod[?(@.method=='JazzCash')].amount").value(500))
				.andExpect(jsonPath("$.financial.trend.values", hasSize(13)));
	}

	@Test
	void theUnpaidInvoiceListIsLargestBalanceFirstAndSkipsVoided() throws Exception {
		report(asAssistant)
				.andExpect(jsonPath("$.outstandingInvoices", hasSize(3)))
				.andExpect(jsonPath("$.outstandingInvoices[*].balance", contains(1500.0, 1500.0, 400.0)))
				.andExpect(jsonPath("$.outstandingInvoices[2].patientName").value("Patient Three"));
	}

	@Test
	void servicesAreGroupedByWhatWasBilled() throws Exception {
		report(asAssistant)
				.andExpect(jsonPath("$.services", hasSize(1)))
				.andExpect(jsonPath("$.services[0].name").value("Consultation"))
				.andExpect(jsonPath("$.services[0].count").value(3))
				.andExpect(jsonPath("$.services[0].billed").value(4500));
	}

	@Test
	void followUpCountsAreCurrentAndTheRateUsesWhatBecameDue() throws Exception {
		report(asAssistant)
				// only the follow-up planned at a visit inside the range counts as "created"; the others
				// come from visits weeks earlier
				.andExpect(jsonPath("$.followUps.created").value(1))
				.andExpect(jsonPath("$.followUps.upcoming").value(1))
				.andExpect(jsonPath("$.followUps.dueToday").value(0))
				.andExpect(jsonPath("$.followUps.overdue").value(1))
				.andExpect(jsonPath("$.followUps.completed").value(1))
				.andExpect(jsonPath("$.followUps.cancelled").value(1))
				// completed / (overdue + due today + completed + cancelled) = 1 / 3
				.andExpect(jsonPath("$.followUps.completionRate").value(org.hamcrest.Matchers.closeTo(33.33, 0.01)))
				.andExpect(jsonPath("$.followUps.overdueList", hasSize(1)))
				.andExpect(jsonPath("$.followUps.overdueList[0].patientName").value("Patient Two"))
				.andExpect(jsonPath("$.followUps.overdueList[0].daysOverdue").value(6));
	}

	@Test
	void eachDoctorHasTheirOwnRow() throws Exception {
		report(asAssistant)
				.andExpect(jsonPath("$.doctors", hasSize(2)))
				.andExpect(jsonPath("$.doctors[?(@.name=='Dr. A')].patientsSeen").value(2))
				.andExpect(jsonPath("$.doctors[?(@.name=='Dr. A')].consultations").value(2))
				.andExpect(jsonPath("$.doctors[?(@.name=='Dr. A')].appointments").value(5))
				.andExpect(jsonPath("$.doctors[?(@.name=='Dr. A')].followUps").value(1))
				// paid on invoices issued in the range: 1000 + 500
				.andExpect(jsonPath("$.doctors[?(@.name=='Dr. A')].collection").value(1500))
				.andExpect(jsonPath("$.doctors[?(@.name=='Dr. B')].patientsSeen").value(1))
				.andExpect(jsonPath("$.doctors[?(@.name=='Dr. B')].collection").value(0));
	}

	// ===== one doctor =====

	@Test
	void theAssistantCanNarrowToOneDoctor() throws Exception {
		report(asAssistant, "doctorId", String.valueOf(docB.getId()))
				.andExpect(jsonPath("$.doctorId").value(docB.getId()))
				.andExpect(jsonPath("$.patients.seen").value(1))
				.andExpect(jsonPath("$.appointments.due").value(0))
				.andExpect(jsonPath("$.financial.billed").value(1500))
				.andExpect(jsonPath("$.financial.collected").value(0))
				.andExpect(jsonPath("$.doctors", hasSize(1)));
	}

	@Test
	void aDoctorOnlyEverSeesTheirOwnFigures() throws Exception {
		report(asDocA, "doctorId", String.valueOf(docB.getId()))
				.andExpect(jsonPath("$.doctorId").value(docA.getId()))
				.andExpect(jsonPath("$.patients.seen").value(2))
				.andExpect(jsonPath("$.financial.billed").value(3000))
				.andExpect(jsonPath("$.doctors", hasSize(1)))
				.andExpect(jsonPath("$.doctors[0].name").value("Dr. A"));
	}

	// ===== ranges, defaults, safety =====

	@Test
	void aSingleDayDefaultsToTodayAndUsesDailyBuckets() throws Exception {
		mvc.perform(get("/api/v1/reports/summary").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.from").value(today.toString()))
				.andExpect(jsonPath("$.to").value(today.toString()))
				.andExpect(jsonPath("$.patients.seen").value(1))
				.andExpect(jsonPath("$.patients.trend.labels", hasSize(1)));
	}

	@Test
	void longRangesSwitchToWeeklyAndMonthlyBuckets() throws Exception {
		mvc.perform(get("/api/v1/reports/summary").param("from", today.minusDays(59).toString())
				.param("to", today.toString()).header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.patients.trend.labels[0]").value("Week 1"));
		mvc.perform(get("/api/v1/reports/summary").param("from", today.minusDays(299).toString())
				.param("to", today.toString()).header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.patients.trend.labels[0]").value(org.hamcrest.Matchers.matchesPattern("[A-Z][a-z]{2} \\d{2}")));
	}

	@Test
	void badRangesAreRefused() throws Exception {
		mvc.perform(get("/api/v1/reports/summary").param("from", today.toString()).param("to", today.minusDays(1).toString())
				.header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RANGE"));
		mvc.perform(get("/api/v1/reports/summary").param("from", today.minusDays(2000).toString()).param("to", today.toString())
				.header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("RANGE_TOO_LARGE"));
	}

	@Test
	void anotherClinicSeesNothingOfOurs() throws Exception {
		String asStranger = bearer(newUser(otherClinic().getId(), "stranger@other.local", "Stranger", Role.ASSISTANT));
		report(asStranger)
				.andExpect(jsonPath("$.patients.seen").value(0))
				.andExpect(jsonPath("$.queue.tokensIssued").value(0))
				.andExpect(jsonPath("$.financial.outstanding").value(0))
				.andExpect(jsonPath("$.followUps.overdue").value(0))
				.andExpect(jsonPath("$.doctors", hasSize(0)));
	}

	@Test
	void needsALogin() throws Exception {
		mvc.perform(get("/api/v1/reports/summary")).andExpect(status().isUnauthorized());
	}
}
