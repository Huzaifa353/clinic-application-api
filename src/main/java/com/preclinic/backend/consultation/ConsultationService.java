package com.preclinic.backend.consultation;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.preclinic.backend.common.ApiException;
import com.preclinic.backend.common.AuditService;
import com.preclinic.backend.common.ClinicClock;
import com.preclinic.backend.common.NumberSequenceService;
import com.preclinic.backend.common.PageResult;
import com.preclinic.backend.common.SequenceKind;
import com.preclinic.backend.consultation.ConsultationDtos.ConsultationContext;
import com.preclinic.backend.consultation.ConsultationDtos.ConsultationDto;
import com.preclinic.backend.consultation.ConsultationDtos.ConsultationRequest;
import com.preclinic.backend.consultation.ConsultationDtos.ConsultationStats;
import com.preclinic.backend.consultation.ConsultationDtos.ExaminationFindingDto;
import com.preclinic.backend.consultation.ConsultationDtos.ExaminationFindingInput;
import com.preclinic.backend.consultation.ConsultationDtos.FollowUpDto;
import com.preclinic.backend.consultation.ConsultationDtos.FollowUpInput;
import com.preclinic.backend.consultation.ConsultationDtos.InvestigationOrderDto;
import com.preclinic.backend.consultation.ConsultationDtos.InvestigationOrderInput;
import com.preclinic.backend.consultation.ConsultationDtos.InvestigationResultRequest;
import com.preclinic.backend.consultation.ConsultationDtos.PrescriptionItemDto;
import com.preclinic.backend.consultation.ConsultationDtos.PrescriptionItemInput;
import com.preclinic.backend.event.EventBus;
import com.preclinic.backend.medicine.MedicineService;
import com.preclinic.backend.patient.PatientService;
import com.preclinic.backend.queue.QueueDtos.QueueItemDto;
import com.preclinic.backend.queue.QueueDtos.VitalsDto;
import com.preclinic.backend.queue.QueueService;
import com.preclinic.backend.security.CurrentUser;

/**
 * The doctor's consultation: symptoms, diagnoses, examination, investigations, prescription and the
 * follow-up plan. One consultation belongs to exactly one visit (never to a token - tokens repeat
 * daily). Saving is a single transaction that replaces the whole document, counts medicine usage,
 * maintains the follow-up and, when asked, completes the visit in the queue.
 */
@Service
public class ConsultationService {

	private static final String BASE = """
			select c.id, c.consult_no, c.visit_id, v.token_no, v.queue_date, c.patient_id, p.name as patient_name,
			       p.patient_no, c.doctor_id, d.full_name as doctor_name, c.notes, c.created_at, c.updated_at,
			       v.bp_systolic, v.bp_diastolic, v.temperature_f, v.pulse, v.weight_kg, v.spo2
			from consultation c
			join visit v on v.id = c.visit_id
			join patient p on p.id = c.patient_id
			join app_user d on d.id = c.doctor_id
			""";

	public record Query(Long patientId, Long doctorId, Long excludeVisitId, LocalDate from, LocalDate to,
			Boolean hasPrescription, String q, String sort, String dir, int skip, int limit) {
	}

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ClinicClock clock;
	private final NumberSequenceService sequences;
	private final QueueService queue;
	private final PatientService patients;
	private final MedicineService medicines;
	private final AuditService audit;
	private final EventBus events;

	public ConsultationService(JdbcClient jdbc, CurrentUser currentUser, ClinicClock clock,
			NumberSequenceService sequences, QueueService queue, PatientService patients, MedicineService medicines,
			AuditService audit, EventBus events) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.clock = clock;
		this.sequences = sequences;
		this.queue = queue;
		this.patients = patients;
		this.medicines = medicines;
		this.audit = audit;
		this.events = events;
	}

	// ===== reads =====================================================================

	@Transactional(readOnly = true)
	public ConsultationDto get(long id) {
		List<ConsultationDto> found = jdbc.sql(BASE + " where c.id = :id and c.clinic_id = :c")
				.param("id", id).param("c", currentUser.clinicId()).query(mapper()).list();
		if (found.isEmpty()) {
			throw ApiException.notFound("CONSULTATION_NOT_FOUND", "No such consultation.");
		}
		return withChildren(found).get(0);
	}

	@Transactional(readOnly = true)
	public ConsultationDto findByVisit(long visitId) {
		List<ConsultationDto> found = jdbc.sql(BASE + " where c.visit_id = :v and c.clinic_id = :c")
				.param("v", visitId).param("c", currentUser.clinicId()).query(mapper()).list();
		return found.isEmpty() ? null : withChildren(found).get(0);
	}

	@Transactional(readOnly = true)
	public PageResult<ConsultationDto> list(Query q) {
		Map<String, Object> params = new HashMap<>();
		params.put("c", currentUser.clinicId());
		StringBuilder where = new StringBuilder(" where c.clinic_id = :c");
		if (q.patientId() != null) {
			where.append(" and c.patient_id = :patientId");
			params.put("patientId", q.patientId());
		}
		if (q.doctorId() != null) {
			where.append(" and c.doctor_id = :doctorId");
			params.put("doctorId", q.doctorId());
		}
		if (q.excludeVisitId() != null) {
			where.append(" and c.visit_id <> :excludeVisit");
			params.put("excludeVisit", q.excludeVisitId());
		}
		if (q.from() != null) {
			where.append(" and v.queue_date >= :from");
			params.put("from", q.from());
		}
		if (q.to() != null) {
			where.append(" and v.queue_date <= :to");
			params.put("to", q.to());
		}
		if (Boolean.TRUE.equals(q.hasPrescription())) {
			where.append(" and exists (select 1 from prescription_item pi where pi.consultation_id = c.id)");
		}
		if (q.q() != null && !q.q().isBlank()) {
			where.append(" and (lower(p.name) like :text escape '\\'")
					.append(" or lower('p' || lpad(p.patient_no::text, 5, '0')) like :text escape '\\'")
					.append(" or lower(d.full_name) like :text escape '\\'")
					.append(" or lower('c' || lpad(c.consult_no::text, 5, '0')) like :text escape '\\'")
					.append(" or exists (select 1 from prescription_item pi where pi.consultation_id = c.id")
					.append("      and lower(pi.medicine_name) like :text escape '\\')")
					.append(" or exists (select 1 from consultation_diagnosis cd where cd.consultation_id = c.id")
					.append("      and lower(cd.name) like :text escape '\\'))");
			params.put("text", "%" + q.q().trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%")
					.replace("_", "\\_") + "%");
		}
		long total = jdbc.sql("select count(*) from consultation c join visit v on v.id = c.visit_id"
				+ " join patient p on p.id = c.patient_id join app_user d on d.id = c.doctor_id" + where)
				.params(params).query(Long.class).single();
		params.put("limit", q.limit());
		params.put("skip", q.skip());
		List<ConsultationDto> page = jdbc.sql(BASE + where + " order by " + orderBy(q) + " limit :limit offset :skip")
				.params(params).query(mapper()).list();
		return new PageResult<>(withChildren(page), total);
	}

	private static String orderBy(Query q) {
		String dir = "asc".equalsIgnoreCase(q.dir()) ? " asc" : " desc";
		String key = q.sort() == null ? "date" : q.sort();
		return switch (key) {
			case "date" -> "v.queue_date" + dir + ", c.id" + dir;
			case "patient" -> "lower(p.name)" + dir + ", c.id";
			case "doctor" -> "lower(d.full_name)" + dir + ", c.id";
			default -> throw ApiException.badRequest("INVALID_SORT", "Unknown sort: " + key);
		};
	}

	/** The prescriptions screen's cards: consultations with medicines today, this week, and by how many doctors. */
	@Transactional(readOnly = true)
	public ConsultationStats stats() {
		LocalDate today = clock.today();
		return jdbc.sql("""
				select count(*) filter (where v.queue_date = :today) as today,
				       count(*) filter (where v.queue_date >= :weekStart) as this_week,
				       count(distinct c.doctor_id) as doctors
				from consultation c join visit v on v.id = c.visit_id
				where c.clinic_id = :c and exists (select 1 from prescription_item pi where pi.consultation_id = c.id)
				""").param("c", currentUser.clinicId()).param("today", today).param("weekStart", today.minusDays(7))
				.query(ConsultationStats.class).single();
	}

	/** What the consultation room needs for one queue entry. */
	@Transactional(readOnly = true)
	public ConsultationContext context(long visitId) {
		QueueItemDto visit = queue.get(visitId);
		ConsultationDto current = findByVisit(visitId);
		List<ConsultationDto> history = list(new Query(visit.patientId(), null, visitId, null, null, null, null, "date",
				"desc", 0, 50)).data();
		QueueItemDto next = queue.list(clock.today(), "waiting", null, null).stream()
				.filter(i -> i.id() != visitId).findFirst().orElse(null);
		return new ConsultationContext(visit, patients.get(visit.patientId()), current, history, next);
	}

	// ===== save ======================================================================

	/**
	 * Stores the consultation for a visit that is with the doctor (or already finished and being
	 * corrected). With {@code complete} the visit is also moved to "completed" in the queue, which is
	 * what "Print" and "Print and next patient" do.
	 */
	@Transactional
	public ConsultationDto save(long visitId, ConsultationRequest r, boolean complete) {
		long clinicId = currentUser.clinicId();
		var visit = jdbc.sql("select patient_id, status, queue_date from visit where id = :id and clinic_id = :c for update")
				.param("id", visitId).param("c", clinicId)
				.query((rs, i) -> new Object[] { rs.getLong("patient_id"), rs.getString("status"),
						rs.getObject("queue_date", LocalDate.class) })
				.optional().orElseThrow(() -> ApiException.notFound("QUEUE_ITEM_NOT_FOUND", "No such queue entry."));
		long patientId = (Long) visit[0];
		String status = (String) visit[1];
		LocalDate visitDate = (LocalDate) visit[2];
		if (!status.equals("consulting") && !status.equals("completed")) {
			throw ApiException.unprocessable("VISIT_NOT_IN_CONSULTATION",
					"Start the consultation (call the patient) before recording it.");
		}

		Long existingId = jdbc.sql("select id from consultation where visit_id = :v").param("v", visitId)
				.query(Long.class).optional().orElse(null);
		long id;
		Set<Long> productsBefore = new HashSet<>();
		if (existingId == null) {
			long no = sequences.next(clinicId, SequenceKind.CONSULTATION);
			id = jdbc.sql("""
					insert into consultation (clinic_id, visit_id, patient_id, doctor_id, consult_no, notes)
					values (:c, :v, :p, :d, :no, :notes) returning id
					""").param("c", clinicId).param("v", visitId).param("p", patientId).param("d", currentUser.id())
					.param("no", no).param("notes", r.notes() == null ? "" : r.notes().trim()).query(Long.class).single();
		}
		else {
			id = existingId;
			productsBefore.addAll(jdbc.sql("select distinct product_id from prescription_item where consultation_id = :id"
					+ " and product_id is not null").param("id", id).query(Long.class).list());
			jdbc.sql("update consultation set notes = :notes, doctor_id = :d, updated_at = now() where id = :id")
					.param("notes", r.notes() == null ? "" : r.notes().trim()).param("d", currentUser.id())
					.param("id", id).update();
			for (String table : List.of("consultation_symptom", "consultation_diagnosis", "consultation_examination",
					"consultation_investigation", "prescription_item")) {
				jdbc.sql("delete from " + table + " where consultation_id = :id").param("id", id).update();
			}
		}

		writeNames("consultation_symptom", "symptom_catalog", id, r.symptoms());
		writeNames("consultation_diagnosis", "diagnosis_catalog", id, r.diagnoses());
		writeExamination(id, r.examinationFindings());
		writeInvestigations(id, r.investigationOrders());
		Set<Long> productsAfter = writePrescription(id, r.prescription());

		Set<Long> newlyPrescribed = new LinkedHashSet<>(productsAfter);
		newlyPrescribed.removeAll(productsBefore);
		if (!newlyPrescribed.isEmpty()) {
			medicines.recordUsage(clinicId, new ArrayList<>(newlyPrescribed));
		}
		writeFollowUp(id, patientId, visitDate, r.followUp());

		if (complete && !status.equals("completed")) {
			queue.changeStatus(visitId, "completed");
		}
		audit.log(existingId == null ? "consultation.create" : "consultation.update", "consultation", id,
				AuditService.detail("visitId", visitId, "medicines", productsAfter.size(), "completed", complete));
		events.publish(clinicId, EventBus.Type.CONSULTATION);
		events.publish(clinicId, EventBus.Type.FOLLOW_UP);
		return get(id);
	}

	/** Symptoms / diagnoses: trimmed, de-duplicated ignoring case, linked to the catalog entry when one exists. */
	private void writeNames(String table, String catalogTable, long consultationId, List<String> names) {
		if (names == null) {
			return;
		}
		Set<String> seen = new HashSet<>();
		int position = 0;
		for (String raw : names) {
			String name = raw.trim().replaceAll("\\s+", " ");
			if (name.isEmpty() || !seen.add(name.toLowerCase())) {
				continue;
			}
			jdbc.sql("insert into " + table + " (consultation_id, position, name, catalog_id) values (:c, :p, :n,"
					+ " (select id from " + catalogTable + " where clinic_id = :clinic and lower(name) = lower(:n)))")
					.param("c", consultationId).param("p", position++).param("n", name)
					.param("clinic", currentUser.clinicId()).update();
		}
	}

	private void writeExamination(long consultationId, List<ExaminationFindingInput> findings) {
		if (findings == null) {
			return;
		}
		for (ExaminationFindingInput f : findings) {
			jdbc.sql("insert into consultation_examination (consultation_id, category, finding, custom_finding)"
					+ " values (:c, :cat, :f, :custom)")
					.param("c", consultationId).param("cat", f.category().trim()).param("f", f.finding().trim())
					.param("custom", blankToNull(f.customFinding())).update();
		}
	}

	private void writeInvestigations(long consultationId, List<InvestigationOrderInput> orders) {
		if (orders == null) {
			return;
		}
		for (InvestigationOrderInput o : orders) {
			jdbc.sql("""
					insert into consultation_investigation (consultation_id, catalog_id, name, is_custom, status, result_note)
					values (:c,
					        (select id from investigation_catalog where clinic_id = :clinic and code = :code),
					        :name, :custom, :status, :note)
					""")
					.param("c", consultationId).param("clinic", currentUser.clinicId())
					.param("code", blankToNull(o.investigationId())).param("name", o.investigationName().trim())
					.param("custom", Boolean.TRUE.equals(o.isCustom()))
					.param("status", o.status() == null ? "Ordered" : o.status()).param("note", blankToNull(o.resultNote()))
					.update();
		}
	}

	/** Returns the medicine product ids on the prescription. Each product must be visible to the clinic. */
	private Set<Long> writePrescription(long consultationId, List<PrescriptionItemInput> items) {
		Set<Long> products = new LinkedHashSet<>();
		if (items == null) {
			return products;
		}
		for (PrescriptionItemInput item : items) {
			if (item.productId() != null) {
				products.add(item.productId());
			}
		}
		if (!products.isEmpty()) {
			List<Long> known = jdbc.sql("select p.id from medicine_product p where p.id in (:ids)"
					+ " and (p.clinic_id is null or p.clinic_id = :c)").param("ids", new ArrayList<>(products))
					.param("c", currentUser.clinicId()).query(Long.class).list();
			if (known.size() != products.size()) {
				throw ApiException.unprocessable("UNKNOWN_MEDICINE", "A prescribed medicine is not in the medicine database.");
			}
		}
		int position = 0;
		for (PrescriptionItemInput item : items) {
			jdbc.sql("""
					insert into prescription_item (consultation_id, position, medicine_name, product_id, frequency, duration,
					       timing, notes)
					values (:c, :pos, :name, :product, :freq, :duration, :timing, :notes)
					""")
					.param("c", consultationId).param("pos", position++).param("name", item.medicine().trim())
					.param("product", item.productId()).param("freq", blankToNull(item.frequency()))
					.param("duration", blankToNull(item.duration())).param("timing", blankToNull(item.instructions()))
					.param("notes", blankToNull(item.notes())).update();
		}
		return products;
	}

	/**
	 * Enabled: create or update the plan (due date = visit date + days; a reschedule, contact status or
	 * resolution already recorded is kept). Disabled or absent: remove it.
	 */
	private void writeFollowUp(long consultationId, long patientId, LocalDate visitDate, FollowUpInput input) {
		boolean enabled = input != null && Boolean.TRUE.equals(input.enabled());
		if (!enabled) {
			jdbc.sql("delete from follow_up where consultation_id = :c").param("c", consultationId).update();
			return;
		}
		int days = input.days() == null ? 7 : input.days();
		jdbc.sql("""
				insert into follow_up (clinic_id, consultation_id, patient_id, days, reason, due_date)
				values (:clinic, :c, :p, :days, :reason, :due)
				on conflict (consultation_id)
				do update set days = excluded.days, reason = excluded.reason, due_date = excluded.due_date
				""")
				.param("clinic", currentUser.clinicId()).param("c", consultationId).param("p", patientId)
				.param("days", days).param("reason", blankToNull(input.reason())).param("due", visitDate.plusDays(days))
				.update();
	}

	/** Records an investigation result (marks it Completed and stores the note) on a saved consultation. */
	@Transactional
	public ConsultationDto updateInvestigation(long consultationId, long orderId, InvestigationResultRequest r) {
		int changed = jdbc.sql("""
				update consultation_investigation ci set status = :status, result_note = :note
				where ci.id = :order and ci.consultation_id = :c
				  and exists (select 1 from consultation x where x.id = ci.consultation_id and x.clinic_id = :clinic)
				""")
				.param("status", r.status()).param("note", blankToNull(r.resultNote())).param("order", orderId)
				.param("c", consultationId).param("clinic", currentUser.clinicId()).update();
		if (changed == 0) {
			throw ApiException.notFound("INVESTIGATION_NOT_FOUND", "No such investigation order.");
		}
		events.publish(currentUser.clinicId(), EventBus.Type.CONSULTATION);
		return get(consultationId);
	}

	// ===== mapping ===================================================================

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private RowMapper<ConsultationDto> mapper() {
		return (rs, i) -> {
			long no = rs.getLong("consult_no");
			long patientNo = rs.getLong("patient_no");
			VitalsDto vitals = new VitalsDto(nullableInt(rs, "bp_systolic"), nullableInt(rs, "bp_diastolic"),
					rs.getBigDecimal("temperature_f"), nullableInt(rs, "pulse"), rs.getBigDecimal("weight_kg"),
					nullableInt(rs, "spo2"));
			return new ConsultationDto(rs.getLong("id"), no, "C" + String.format("%05d", no), rs.getLong("visit_id"),
					String.format("%02d", rs.getInt("token_no")), rs.getObject("queue_date", LocalDate.class),
					rs.getLong("patient_id"), rs.getString("patient_name"), "P" + String.format("%05d", patientNo),
					rs.getLong("doctor_id"), rs.getString("doctor_name"), rs.getString("notes"), List.of(), List.of(),
					List.of(), List.of(), null, List.of(), vitals, rs.getObject("created_at", OffsetDateTime.class).toInstant(),
					rs.getObject("updated_at", OffsetDateTime.class).toInstant());
		};
	}

	private static Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}

	/** Attaches the child rows with one query per kind for the whole page. */
	List<ConsultationDto> withChildren(List<ConsultationDto> rows) {
		if (rows.isEmpty()) {
			return rows;
		}
		List<Long> ids = rows.stream().map(ConsultationDto::id).toList();
		LocalDate today = clock.today();

		Map<Long, List<String>> symptoms = names("consultation_symptom", ids);
		Map<Long, List<String>> diagnoses = names("consultation_diagnosis", ids);

		Map<Long, List<ExaminationFindingDto>> examination = new HashMap<>();
		jdbc.sql("select id, consultation_id, category, finding, custom_finding from consultation_examination"
				+ " where consultation_id in (:ids) order by id").param("ids", ids)
				.query((rs, i) -> Map.entry(rs.getLong("consultation_id"), new ExaminationFindingDto(rs.getLong("id"),
						rs.getString("category"), rs.getString("finding"), rs.getString("custom_finding"))))
				.list().forEach(e -> examination.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));

		Map<Long, List<InvestigationOrderDto>> investigations = new HashMap<>();
		jdbc.sql("""
				select ci.id, ci.consultation_id, ic.code, ci.name, ci.is_custom, ci.status, ci.result_note
				from consultation_investigation ci left join investigation_catalog ic on ic.id = ci.catalog_id
				where ci.consultation_id in (:ids) order by ci.id
				""").param("ids", ids)
				.query((rs, i) -> Map.entry(rs.getLong("consultation_id"), new InvestigationOrderDto(rs.getLong("id"),
						rs.getString("code"), rs.getString("name"), rs.getBoolean("is_custom"), rs.getString("status"),
						rs.getString("result_note"))))
				.list().forEach(e -> investigations.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));

		Map<Long, List<PrescriptionItemDto>> prescriptions = new HashMap<>();
		jdbc.sql("select id, consultation_id, medicine_name, product_id, frequency, duration, timing, notes"
				+ " from prescription_item where consultation_id in (:ids) order by consultation_id, position")
				.param("ids", ids)
				.query((rs, i) -> {
					long product = rs.getLong("product_id");
					Long productId = rs.wasNull() ? null : product;
					return Map.entry(rs.getLong("consultation_id"), new PrescriptionItemDto(rs.getLong("id"),
							rs.getString("medicine_name"), productId, rs.getString("frequency"), rs.getString("duration"),
							rs.getString("timing"), rs.getString("notes")));
				})
				.list().forEach(e -> prescriptions.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));

		Map<Long, FollowUpDto> followUps = new LinkedHashMap<>();
		jdbc.sql("""
				select id, consultation_id, days, reason, due_date, override_due_date, status, contact_status,
				       last_reminded_on, linked_appointment_id, cancellation_reason, resolved_at
				from follow_up where consultation_id in (:ids)
				""").param("ids", ids)
				.query((rs, i) -> {
					LocalDate original = rs.getObject("due_date", LocalDate.class);
					LocalDate override = rs.getObject("override_due_date", LocalDate.class);
					LocalDate effective = override != null ? override : original;
					String status = rs.getString("status");
					long appt = rs.getLong("linked_appointment_id");
					Long linked = rs.wasNull() ? null : appt;
					OffsetDateTime resolved = rs.getObject("resolved_at", OffsetDateTime.class);
					return Map.entry(rs.getLong("consultation_id"), new FollowUpDto(rs.getLong("id"), rs.getInt("days"),
							rs.getString("reason"), effective, original, followUpState(status, effective, today), status,
							rs.getString("contact_status"), rs.getObject("last_reminded_on", LocalDate.class), linked,
							rs.getString("cancellation_reason"), resolved == null ? null : resolved.toInstant()));
				})
				.list().forEach(e -> followUps.put(e.getKey(), e.getValue()));

		return rows.stream().map(c -> new ConsultationDto(c.id(), c.consultNo(), c.displayId(), c.visitId(), c.tokenNo(),
				c.visitDate(), c.patientId(), c.patientName(), c.patientDisplayId(), c.doctorId(), c.doctorName(),
				c.notes(), symptoms.getOrDefault(c.id(), List.of()), diagnoses.getOrDefault(c.id(), List.of()),
				examination.getOrDefault(c.id(), List.of()), investigations.getOrDefault(c.id(), List.of()),
				followUps.get(c.id()), prescriptions.getOrDefault(c.id(), List.of()), c.vitals(), c.createdAt(),
				c.updatedAt())).toList();
	}

	private Map<Long, List<String>> names(String table, List<Long> ids) {
		Map<Long, List<String>> result = new HashMap<>();
		jdbc.sql("select consultation_id, name from " + table + " where consultation_id in (:ids)"
				+ " order by consultation_id, position").param("ids", ids)
				.query((rs, i) -> Map.entry(rs.getLong("consultation_id"), rs.getString("name")))
				.list().forEach(e -> result.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
		return result;
	}

	/** Derived every time it is read, never stored: only the explicit outcome (completed/cancelled) is. */
	public static String followUpState(String status, LocalDate effectiveDue, LocalDate today) {
		if ("completed".equals(status)) {
			return "Completed";
		}
		if ("cancelled".equals(status)) {
			return "Cancelled";
		}
		if (effectiveDue.isEqual(today)) {
			return "Due";
		}
		return effectiveDue.isBefore(today) ? "Overdue" : "Upcoming";
	}
}
