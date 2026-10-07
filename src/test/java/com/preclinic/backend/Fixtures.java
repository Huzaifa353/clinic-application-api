package com.preclinic.backend;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Inserts rows straight into the database so a test can set up exactly the state it needs (visits in
 * the past, partly paid invoices, open follow-ups ...) without going through the API under test.
 * All methods return the new row's id.
 */
public class Fixtures {

	private final JdbcClient jdbc;
	private final long clinicId;

	public Fixtures(JdbcClient jdbc, long clinicId) {
		this.jdbc = jdbc;
		this.clinicId = clinicId;
	}

	public long patient(String name, String mobile) {
		String norm = mobile.replaceAll("[^0-9]", "");
		return jdbc.sql("""
				insert into patient (clinic_id, patient_no, name, mobile, mobile_norm)
				values (:c, (select coalesce(max(patient_no), 0) + 1 from patient where clinic_id = :c), :name, :mobile, :norm)
				returning id
				""").param("c", clinicId).param("name", name).param("mobile", mobile).param("norm", norm)
				.query(Long.class).single();
	}

	public void patientRegisteredDaysAgo(long patientId, int days) {
		jdbc.sql("update patient set registered_at = now() - make_interval(days => :d) where id = :id")
				.param("d", days).param("id", patientId).update();
	}

	public void setGender(long patientId, String gender) {
		jdbc.sql("update patient set gender = :g where id = :id").param("g", gender).param("id", patientId).update();
	}

	public void setCnic(long patientId, String cnic) {
		jdbc.sql("update patient set cnic = :cnic where id = :id").param("cnic", cnic).param("id", patientId).update();
	}

	public long visit(long patientId, Long doctorId, LocalDate date, String status) {
		return jdbc.sql("""
				insert into visit (clinic_id, queue_date, token_no, patient_id, doctor_id, source, status, sort_order)
				values (:c, :date,
				        (select coalesce(max(token_no), 0) + 1 from visit where clinic_id = :c and queue_date = :date),
				        :p, :d, 'walk-in', :status,
				        (select coalesce(max(sort_order), 0) + 1 from visit where clinic_id = :c and queue_date = :date))
				returning id
				""").param("c", clinicId).param("date", date).param("p", patientId).param("d", doctorId)
				.param("status", status).query(Long.class).single();
	}

	/** A visit that came from an appointment (the appointment is marked checked-in, like real intake does). */
	public long visitFromAppointment(long appointmentId, long patientId, Long doctorId, LocalDate date, String status) {
		long visit = jdbc.sql("""
				insert into visit (clinic_id, queue_date, token_no, patient_id, doctor_id, appointment_id, source, status, sort_order)
				values (:c, :date,
				        (select coalesce(max(token_no), 0) + 1 from visit where clinic_id = :c and queue_date = :date),
				        :p, :d, :a, 'appointment', :status,
				        (select coalesce(max(sort_order), 0) + 1 from visit where clinic_id = :c and queue_date = :date))
				returning id
				""").param("c", clinicId).param("date", date).param("p", patientId).param("d", doctorId)
				.param("a", appointmentId).param("status", status).query(Long.class).single();
		jdbc.sql("update appointment set status = 'checked-in' where id = :a").param("a", appointmentId).update();
		return visit;
	}

	public long consultation(long visitId, long patientId, long doctorId) {
		return jdbc.sql("""
				insert into consultation (clinic_id, visit_id, patient_id, doctor_id, consult_no)
				values (:c, :v, :p, :d, (select coalesce(max(consult_no), 0) + 1 from consultation where clinic_id = :c))
				returning id
				""").param("c", clinicId).param("v", visitId).param("p", patientId).param("d", doctorId)
				.query(Long.class).single();
	}

	/** An open follow-up (status null) due on the given date. */
	public long followUp(long consultationId, long patientId, LocalDate due) {
		return jdbc.sql("""
				insert into follow_up (clinic_id, consultation_id, patient_id, days, due_date)
				values (:c, :con, :p, 7, :due) returning id
				""").param("c", clinicId).param("con", consultationId).param("p", patientId).param("due", due)
				.query(Long.class).single();
	}

	/** Visit + consultation + open follow-up in one go. */
	public long followUpFor(long patientId, long doctorId, LocalDate visitDate, LocalDate due) {
		long visit = visit(patientId, doctorId, visitDate, "completed");
		long consultation = consultation(visit, patientId, doctorId);
		return followUp(consultation, patientId, due);
	}

	public long invoice(long patientId, Long visitId, BigDecimal fee) {
		return jdbc.sql("""
				insert into invoice (clinic_id, invoice_no, patient_id, visit_id, consultation_fee, total)
				values (:c, (select coalesce(max(invoice_no), 0) + 1 from invoice where clinic_id = :c), :p, :v, :fee, :fee)
				returning id
				""").param("c", clinicId).param("p", patientId).param("v", visitId).param("fee", fee)
				.query(Long.class).single();
	}

	/** A payment that was received some days ago (payments cannot be edited afterwards, so it is inserted that way). */
	public void paymentDaysAgo(long invoiceId, BigDecimal amount, int days) {
		jdbc.sql("""
				insert into payment (clinic_id, receipt_no, invoice_id, amount, method, paid_at)
				values (:c, (select coalesce(max(receipt_no), 0) + 1 from payment where clinic_id = :c), :i, :a, 'Cash',
				        now() - make_interval(days => :d))
				""").param("c", clinicId).param("i", invoiceId).param("a", amount).param("d", days).update();
	}

	public void payment(long invoiceId, BigDecimal amount) {
		jdbc.sql("""
				insert into payment (clinic_id, receipt_no, invoice_id, amount, method)
				values (:c, (select coalesce(max(receipt_no), 0) + 1 from payment where clinic_id = :c), :i, :a, 'Cash')
				""").param("c", clinicId).param("i", invoiceId).param("a", amount).update();
	}
}
