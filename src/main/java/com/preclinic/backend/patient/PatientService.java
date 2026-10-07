package com.preclinic.backend.patient;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.preclinic.backend.common.ApiException;
import com.preclinic.backend.common.ClinicClock;
import com.preclinic.backend.common.NumberSequenceService;
import com.preclinic.backend.common.PageResult;
import com.preclinic.backend.common.PgArrays;
import com.preclinic.backend.common.SequenceKind;
import com.preclinic.backend.patient.PatientDtos.ClinicalListsRequest;
import com.preclinic.backend.patient.PatientDtos.CreatePatientRequest;
import com.preclinic.backend.patient.PatientDtos.PatientDto;
import com.preclinic.backend.patient.PatientDtos.PatientStats;
import com.preclinic.backend.patient.PatientDtos.UpdatePatientRequest;
import com.preclinic.backend.security.CurrentUser;

@Service
public class PatientService {

	/** Active = seen within this many days (same definition the Patients screen has always used). */
	static final int ACTIVE_DAYS = 90;

	private static final String COLUMNS = """
			select p.id, p.patient_no, p.name, p.mobile, p.gender,
			       coalesce(extract(year from age(:today, p.date_of_birth))::int, p.age_years) as age,
			       p.date_of_birth, p.cnic, p.blood_group, p.address, p.photo_file_id,
			       p.allergies, p.medical_history, p.surgical_history, p.current_medications,
			       (p.registered_at at time zone :tz)::date as registration_date,
			       coalesce(s.total_visits, 0) as total_visits, s.last_visit,
			       (select u.full_name from visit v join app_user u on u.id = v.doctor_id
			         where v.patient_id = p.id and v.status <> 'cancelled'
			         order by v.queue_date desc, v.id desc limit 1) as last_doctor,
			       coalesce(o.outstanding, 0) as outstanding_balance,
			       f.due as next_follow_up_date
			""";

	private static final String FROM = """
			from patient p
			left join patient_visit_stats s on s.patient_id = p.id
			left join patient_outstanding o on o.patient_id = p.id
			left join (select distinct on (patient_id) patient_id,
			                  coalesce(override_due_date, due_date) as due
			           from follow_up where status is null
			           order by patient_id, coalesce(override_due_date, due_date)) f on f.patient_id = p.id
			""";

	private static final String SEARCH = """
			(lower(p.name) like :qText escape '\\'
			 or lower('p' || lpad(p.patient_no::text, 5, '0')) like :qText escape '\\'
			 or p.mobile_norm like :qMobile escape '\\'
			 or replace(coalesce(p.cnic, ''), '-', '') like :qCnic escape '\\')
			""";

	/** Filters/sort/paging of the Patients screen. */
	public record Query(String q, String gender, String status, String followUp, LocalDate registeredFrom,
			LocalDate registeredTo, String sort, String dir, int skip, int limit) {
	}

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ClinicClock clock;
	private final NumberSequenceService sequences;

	public PatientService(JdbcClient jdbc, CurrentUser currentUser, ClinicClock clock, NumberSequenceService sequences) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.clock = clock;
		this.sequences = sequences;
	}

	// --- reads ---------------------------------------------------------------------

	@Transactional(readOnly = true)
	public PatientDto get(long id) {
		return jdbc.sql(COLUMNS + FROM + " where p.id = :id and p.clinic_id = :c")
				.params(base()).param("id", id)
				.query(mapper()).optional()
				.orElseThrow(() -> ApiException.notFound("PATIENT_NOT_FOUND", "No such patient."));
	}

	/** Fails with 404 unless the patient belongs to the caller's clinic. Used by other modules. */
	@Transactional(readOnly = true)
	public void requireExists(long id) {
		Integer found = jdbc.sql("select 1 from patient where id = :id and clinic_id = :c")
				.param("id", id).param("c", currentUser.clinicId()).query(Integer.class).optional().orElse(null);
		if (found == null) {
			throw ApiException.notFound("PATIENT_NOT_FOUND", "No such patient.");
		}
	}

	@Transactional(readOnly = true)
	public PageResult<PatientDto> list(Query query) {
		Map<String, Object> params = base();
		StringBuilder where = new StringBuilder(" where p.clinic_id = :c");
		if (query.q() != null && !query.q().isBlank()) {
			where.append(" and ").append(SEARCH);
			putSearchParams(params, query.q());
		}
		if (query.gender() != null && !query.gender().isBlank()) {
			where.append(" and p.gender = :gender");
			params.put("gender", query.gender());
		}
		if ("Active".equalsIgnoreCase(query.status())) {
			where.append(" and s.last_visit >= cast(:today as date) - ").append(ACTIVE_DAYS);
		}
		else if ("Inactive".equalsIgnoreCase(query.status())) {
			where.append(" and (s.last_visit is null or s.last_visit < cast(:today as date) - ").append(ACTIVE_DAYS).append(')');
		}
		if (query.followUp() != null) {
			switch (query.followUp()) {
				case "None" -> where.append(" and f.due is null");
				case "Due" -> where.append(" and f.due = cast(:today as date)");
				case "Overdue" -> where.append(" and f.due < cast(:today as date)");
				case "Upcoming" -> where.append(" and f.due > cast(:today as date)");
				default -> {
				}
			}
		}
		if (query.registeredFrom() != null) {
			where.append(" and (p.registered_at at time zone :tz)::date >= :regFrom");
			params.put("regFrom", query.registeredFrom());
		}
		if (query.registeredTo() != null) {
			where.append(" and (p.registered_at at time zone :tz)::date <= :regTo");
			params.put("regTo", query.registeredTo());
		}

		long total = jdbc.sql("select count(*) " + FROM + where).params(params).query(Long.class).single();
		params.put("limit", query.limit());
		params.put("skip", query.skip());
		List<PatientDto> page = jdbc.sql(COLUMNS + FROM + where + " order by " + orderBy(query)
				+ " limit :limit offset :skip").params(params).query(mapper()).list();
		return new PageResult<>(page, total);
	}

	/** Whitelisted ORDER BY: user input only ever selects among these constants. */
	private static String orderBy(Query q) {
		boolean desc = q.dir() == null ? defaultDesc(q.sort()) : "desc".equalsIgnoreCase(q.dir());
		String dir = desc ? " desc" : " asc";
		String key = q.sort() == null ? "recentVisit" : q.sort();
		return switch (key) {
			case "name" -> "lower(p.name)" + dir + ", p.id";
			case "id" -> "p.patient_no" + dir;
			case "mobile" -> "p.mobile_norm" + dir + ", p.id";
			case "age" -> "coalesce(extract(year from age(:today, p.date_of_birth))::int, p.age_years)" + dir
					+ " nulls last, p.id";
			case "totalVisits" -> "coalesce(s.total_visits, 0)" + dir + ", p.id";
			case "recentRegistered" -> "p.registered_at" + dir + ", p.id";
			case "recentVisit", "lastVisit" -> "s.last_visit" + dir + " nulls last, p.id";
			default -> throw ApiException.badRequest("INVALID_SORT", "Unknown sort: " + key);
		};
	}

	private static boolean defaultDesc(String sort) {
		return sort == null || sort.equals("recentVisit") || sort.equals("recentRegistered") || sort.equals("lastVisit")
				|| sort.equals("totalVisits");
	}

	/** Fast lookup for the assistant/doctor search boxes: name, patient no., mobile (any format), CNIC. */
	@Transactional(readOnly = true)
	public List<PatientDto> search(String q, int limit) {
		if (q == null || q.isBlank()) {
			return List.of();
		}
		Map<String, Object> params = base();
		putSearchParams(params, q);
		params.put("exactMobile", MobileNumbers.normalize(q));
		params.put("limit", limit);
		return jdbc.sql(COLUMNS + FROM + " where p.clinic_id = :c and " + SEARCH
				+ " order by (p.mobile_norm = :exactMobile) desc, lower(p.name), p.id limit :limit")
				.params(params).query(mapper()).list();
	}

	/** Patients already registered under the same number (written in any format). */
	@Transactional(readOnly = true)
	public List<PatientDto> duplicates(String mobile) {
		String lastTen = MobileNumbers.lastTen(mobile);
		if (lastTen.length() < 7) {
			return List.of();
		}
		Map<String, Object> params = base();
		params.put("lastTen", lastTen);
		return jdbc.sql(COLUMNS + FROM + " where p.clinic_id = :c and right(regexp_replace(p.mobile_norm, '\\D', '', 'g'), 10) = :lastTen"
				+ " order by p.id").params(params).query(mapper()).list();
	}

	@Transactional(readOnly = true)
	public PatientStats stats() {
		return jdbc.sql("""
				select count(*) as total,
				       count(*) filter (where (p.registered_at at time zone :tz)::date
				                              >= date_trunc('month', cast(:today as date))::date) as new_this_month,
				       count(*) filter (where s.last_visit >= cast(:today as date) - %d) as active,
				       (select count(*) from follow_up fu
				         where fu.clinic_id = :c and fu.status is null
				           and coalesce(fu.override_due_date, fu.due_date) <= cast(:today as date)) as follow_ups_due
				from patient p left join patient_visit_stats s on s.patient_id = p.id
				where p.clinic_id = :c
				""".formatted(ACTIVE_DAYS)).params(base()).query(PatientStats.class).single();
	}

	// --- writes --------------------------------------------------------------------

	@Transactional
	public PatientDto create(CreatePatientRequest r) {
		long clinicId = currentUser.clinicId();
		String mobile = r.mobile().trim();
		String normalized = requireValidMobile(mobile);
		long patientNo = sequences.next(clinicId, SequenceKind.PATIENT);
		long id = jdbc.sql("""
				insert into patient (clinic_id, patient_no, name, mobile, mobile_norm, gender, date_of_birth, age_years,
				                     cnic, blood_group, address, allergies, created_by)
				values (:c, :no, :name, :mobile, :norm, :gender, :dob, :age, :cnic, :bg, :address,
				        cast(:allergies as text[]), :user)
				returning id
				""")
				.param("c", clinicId).param("no", patientNo).param("name", r.name().trim())
				.param("mobile", mobile).param("norm", normalized)
				.param("gender", r.gender()).param("dob", r.dateOfBirth())
				.param("age", r.age() == null ? null : r.age().shortValue())
				.param("cnic", normalizeCnic(r.cnic()))
				.param("bg", blankToNull(r.bloodGroup())).param("address", blankToNull(r.address()))
				.param("allergies", PgArrays.literal(r.allergies()))
				.param("user", currentUser.id())
				.query(Long.class).single();
		return get(id);
	}

	@Transactional
	public PatientDto update(long id, UpdatePatientRequest r) {
		String mobile = r.mobile() == null ? null : r.mobile().trim();
		String normalized = mobile == null ? null : requireValidMobile(mobile);
		int changed = jdbc.sql("""
				update patient set
				  name = coalesce(:name, name),
				  mobile = coalesce(:mobile, mobile),
				  mobile_norm = coalesce(:norm, mobile_norm),
				  gender = coalesce(:gender, gender),
				  age_years = coalesce(:age, age_years),
				  date_of_birth = coalesce(:dob, date_of_birth),
				  cnic = case when :cnicSet then :cnic else cnic end,
				  blood_group = case when :bgSet then :bg else blood_group end,
				  address = case when :addressSet then :address else address end,
				  updated_at = now()
				where id = :id and clinic_id = :c
				""")
				.param("name", r.name() == null ? null : r.name().trim())
				.param("mobile", mobile).param("norm", normalized)
				.param("gender", r.gender())
				.param("age", r.age() == null ? null : r.age().shortValue())
				.param("dob", r.dateOfBirth())
				.param("cnicSet", r.cnic() != null).param("cnic", normalizeCnic(r.cnic()))
				.param("bgSet", r.bloodGroup() != null).param("bg", blankToNull(r.bloodGroup()))
				.param("addressSet", r.address() != null).param("address", blankToNull(r.address()))
				.param("id", id).param("c", currentUser.clinicId()).update();
		if (changed == 0) {
			throw ApiException.notFound("PATIENT_NOT_FOUND", "No such patient.");
		}
		return get(id);
	}

	@Transactional
	public PatientDto updateClinical(long id, ClinicalListsRequest r) {
		int changed = jdbc.sql("""
				update patient set allergies = cast(:allergies as text[]), medical_history = cast(:medical as text[]),
				       surgical_history = cast(:surgical as text[]), current_medications = cast(:meds as text[]),
				       updated_at = now()
				where id = :id and clinic_id = :c
				""")
				.param("allergies", PgArrays.literal(r.allergies())).param("medical", PgArrays.literal(r.medicalHistory()))
				.param("surgical", PgArrays.literal(r.surgicalHistory())).param("meds", PgArrays.literal(r.currentMedications()))
				.param("id", id).param("c", currentUser.clinicId()).update();
		if (changed == 0) {
			throw ApiException.notFound("PATIENT_NOT_FOUND", "No such patient.");
		}
		return get(id);
	}

	// --- helpers -------------------------------------------------------------------

	private Map<String, Object> base() {
		Map<String, Object> params = new HashMap<>();
		params.put("c", currentUser.clinicId());
		params.put("today", clock.today());
		params.put("tz", clock.zone().getId());
		return params;
	}

	/** Only a query that looks like a phone number (digits, spaces, dashes, +, brackets) is matched against mobiles. */
	private static final java.util.regex.Pattern NUMBER_LIKE = java.util.regex.Pattern.compile("[0-9+\\-\\s()]+");

	private static void putSearchParams(Map<String, Object> params, String q) {
		String text = q.trim().toLowerCase();
		boolean numberLike = NUMBER_LIKE.matcher(text).matches();
		// A null pattern makes "mobile_norm like null" unknown, i.e. the mobile clause matches nothing.
		params.put("qMobile", numberLike ? like(MobileNumbers.normalize(text)) : null);
		params.put("qText", like(text));
		params.put("qCnic", like(text.replace("-", "")));
	}

	/** Contains-match pattern with the user's own %, _ and \ made literal. */
	private static String like(String value) {
		return "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
	}

	private static String requireValidMobile(String mobile) {
		String normalized = MobileNumbers.normalize(mobile);
		if (!MobileNumbers.isValid(normalized)) {
			throw ApiException.badRequest("INVALID_MOBILE", "Enter a valid mobile number, e.g. 0300-1234567.");
		}
		return normalized;
	}

	/** Returns the CNIC as 12345-1234567-1, null when blank; rejects anything that is not 13 digits. */
	private static String normalizeCnic(String cnic) {
		if (cnic == null || cnic.isBlank()) {
			return null;
		}
		String digits = cnic.replaceAll("\\D", "");
		if (digits.length() != 13) {
			throw ApiException.badRequest("INVALID_CNIC", "A CNIC has 13 digits, e.g. 35202-1234567-8.");
		}
		return digits.substring(0, 5) + "-" + digits.substring(5, 12) + "-" + digits.substring(12);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private RowMapper<PatientDto> mapper() {
		LocalDate today = clock.today();
		return (rs, i) -> {
			LocalDate next = rs.getObject("next_follow_up_date", LocalDate.class);
			int age = rs.getInt("age");
			Integer ageValue = rs.wasNull() ? null : age;
			long no = rs.getLong("patient_no");
			BigDecimal outstanding = rs.getBigDecimal("outstanding_balance");
			return new PatientDto(rs.getLong("id"), no, "P" + String.format("%05d", no), rs.getString("name"),
					rs.getString("mobile"), rs.getString("gender"), ageValue, rs.getObject("date_of_birth", LocalDate.class),
					rs.getString("cnic"), rs.getString("blood_group"), rs.getString("address"), nullableLong(rs, "photo_file_id"),
					PgArrays.read(rs.getArray("allergies")), PgArrays.read(rs.getArray("medical_history")),
					PgArrays.read(rs.getArray("surgical_history")), PgArrays.read(rs.getArray("current_medications")),
					rs.getObject("registration_date", LocalDate.class), rs.getLong("total_visits"),
					rs.getObject("last_visit", LocalDate.class), rs.getString("last_doctor"),
					outstanding == null ? BigDecimal.ZERO : outstanding, followUpLabel(next, today), next);
		};
	}

	private static Long nullableLong(ResultSet rs, String column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}

	static String followUpLabel(LocalDate next, LocalDate today) {
		if (next == null) {
			return "None";
		}
		if (next.isEqual(today)) {
			return "Due";
		}
		return next.isBefore(today) ? "Overdue" : "Upcoming";
	}
}
