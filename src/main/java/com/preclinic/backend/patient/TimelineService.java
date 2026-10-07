package com.preclinic.backend.patient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.preclinic.backend.common.ApiException;
import com.preclinic.backend.common.ClinicClock;
import com.preclinic.backend.common.PgArrays;
import com.preclinic.backend.security.CurrentUser;

/**
 * A patient's whole history as one newest-first feed: consultations, payments, follow-up outcomes
 * and documents/referrals. Diagnoses, examination, investigations and prescription are part of their
 * consultation (one visit = one event), exactly as the profile has always shown them.
 */
@Service
public class TimelineService {

	/**
	 * {@code type}: consultation, payment, followup-resolved, referral or document. Fields not relevant to
	 * a type are null. {@code date} is the clinic-local day the event belongs to (for grouping).
	 */
	public record TimelineEvent(String id, String type, Instant at, LocalDate date, Long consultationId,
			String consultationDisplayId, String doctorName, List<String> diagnoses, int medicines,
			boolean followUpPlanned, String followUpStatus, Long invoiceId, String invoiceDisplayId, Long paymentId,
			String receiptDisplayId, BigDecimal amount, String method, boolean invoiceVoided, Long documentId,
			String fileName, String documentType) {
	}

	private static final String EVENTS = """
			select * from (
			  select 'consultation' as type, 'visit-' || c.id as event_id, c.created_at as at,
			         c.id as consultation_id, c.consult_no, d.full_name as doctor_name,
			         array(select cd.name from consultation_diagnosis cd where cd.consultation_id = c.id order by cd.position) as diagnoses,
			         (select count(*) from prescription_item pi where pi.consultation_id = c.id) as medicines,
			         exists (select 1 from follow_up f where f.consultation_id = c.id) as follow_up_planned,
			         null::text as follow_up_status,
			         null::bigint as invoice_id, null::bigint as invoice_no, null::bigint as payment_id,
			         null::bigint as receipt_no, null::numeric as amount, null::text as method, false as voided,
			         null::bigint as document_id, null::text as file_name, null::text as doc_type
			  from consultation c join app_user d on d.id = c.doctor_id
			  where c.patient_id = :p and c.clinic_id = :c

			  union all
			  select 'followup-resolved', 'followup-' || c.id, f.resolved_at, c.id, c.consult_no, d.full_name,
			         '{}'::text[], 0, true, f.status, null, null, null, null, null, null, false, null, null, null
			  from follow_up f join consultation c on c.id = f.consultation_id join app_user d on d.id = c.doctor_id
			  where f.patient_id = :p and f.clinic_id = :c and f.status is not null and f.resolved_at is not null

			  union all
			  select 'payment', 'txn-' || pm.id, pm.paid_at, null, null, null, '{}'::text[], 0, false, null,
			         i.id, i.invoice_no, pm.id, pm.receipt_no, pm.amount, pm.method, i.voided, null, null, null
			  from payment pm join invoice i on i.id = pm.invoice_id
			  where i.patient_id = :p and pm.clinic_id = :c

			  union all
			  select case when doc.doc_type = 'Referral' then 'referral' else 'document' end, 'doc-' || doc.id,
			         doc.uploaded_at, null, null, null, '{}'::text[], 0, false, null, null, null, null, null, null,
			         null, false, doc.id, doc.file_name, doc.doc_type
			  from patient_document doc where doc.patient_id = :p and doc.clinic_id = :c
			) t
			""";

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ClinicClock clock;
	private final PatientService patients;

	public TimelineService(JdbcClient jdbc, CurrentUser currentUser, ClinicClock clock, PatientService patients) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.clock = clock;
		this.patients = patients;
	}

	/** {@code filter}: all, consultations, payments, followups, referrals or documents (the profile's chips). */
	@Transactional(readOnly = true)
	public List<TimelineEvent> timeline(long patientId, String filter, int skip, int limit) {
		patients.requireExists(patientId);
		String where = switch (filter == null ? "all" : filter) {
			case "all" -> "";
			case "consultations" -> " where t.type = 'consultation'";
			case "payments" -> " where t.type = 'payment'";
			case "followups" -> " where t.type = 'followup-resolved' or (t.type = 'consultation' and t.follow_up_planned)";
			case "referrals" -> " where t.type = 'referral'";
			case "documents" -> " where t.type = 'document'";
			default -> throw ApiException.badRequest("INVALID_FILTER", "Unknown timeline filter: " + filter);
		};
		return jdbc.sql(EVENTS + where + " order by t.at desc, t.event_id desc limit :limit offset :skip")
				.param("p", patientId).param("c", currentUser.clinicId()).param("limit", limit).param("skip", skip)
				.query((rs, i) -> {
					OffsetDateTime at = rs.getObject("at", OffsetDateTime.class);
					long consultationId = rs.getLong("consultation_id");
					boolean hasConsultation = !rs.wasNull();
					long consultNo = rs.getLong("consult_no");
					long invoiceId = rs.getLong("invoice_id");
					boolean hasInvoice = !rs.wasNull();
					long invoiceNo = rs.getLong("invoice_no");
					long paymentId = rs.getLong("payment_id");
					boolean hasPayment = !rs.wasNull();
					long receiptNo = rs.getLong("receipt_no");
					long documentId = rs.getLong("document_id");
					boolean hasDocument = !rs.wasNull();
					return new TimelineEvent(rs.getString("event_id"), rs.getString("type"), at.toInstant(),
							at.atZoneSameInstant(clock.zone()).toLocalDate(), hasConsultation ? consultationId : null,
							hasConsultation ? "C" + String.format("%05d", consultNo) : null, rs.getString("doctor_name"),
							PgArrays.read(rs.getArray("diagnoses")), rs.getInt("medicines"),
							rs.getBoolean("follow_up_planned"), rs.getString("follow_up_status"),
							hasInvoice ? invoiceId : null, hasInvoice ? "INV-" + String.format("%05d", invoiceNo) : null,
							hasPayment ? paymentId : null, hasPayment ? "REC" + String.format("%06d", receiptNo) : null,
							rs.getBigDecimal("amount"), rs.getString("method"), rs.getBoolean("voided"),
							hasDocument ? documentId : null, rs.getString("file_name"), rs.getString("doc_type"));
				}).list();
	}
}
