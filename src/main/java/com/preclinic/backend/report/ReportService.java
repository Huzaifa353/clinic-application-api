package com.preclinic.backend.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.preclinic.backend.common.ApiException;
import com.preclinic.backend.common.ClinicClock;
import com.preclinic.backend.report.ReportDtos.AppointmentsReport;
import com.preclinic.backend.report.ReportDtos.CountSeries;
import com.preclinic.backend.report.ReportDtos.DoctorRow;
import com.preclinic.backend.report.ReportDtos.FinancialReport;
import com.preclinic.backend.report.ReportDtos.FollowUpsReport;
import com.preclinic.backend.report.ReportDtos.MethodAmount;
import com.preclinic.backend.report.ReportDtos.MoneySeries;
import com.preclinic.backend.report.ReportDtos.OutstandingInvoice;
import com.preclinic.backend.report.ReportDtos.OverdueFollowUp;
import com.preclinic.backend.report.ReportDtos.PatientsReport;
import com.preclinic.backend.report.ReportDtos.QueueReport;
import com.preclinic.backend.report.ReportDtos.ReportsData;
import com.preclinic.backend.report.ReportDtos.ServiceRow;
import com.preclinic.backend.security.CurrentUser;
import com.preclinic.backend.user.Role;

/**
 * The Reports & Analytics numbers, aggregated in the database for a date range (clinic-local days,
 * inclusive) and an optional doctor. A doctor only ever sees their own figures; the assistant sees the
 * whole clinic or any one doctor. Dates are the clinic's, so "today" and the day boundaries agree with
 * the rest of the system.
 */
@Service
public class ReportService {

	private static final int MAX_DAYS = 1100;

	record Bucket(String label, LocalDate start, LocalDate end) {
		boolean contains(LocalDate day) {
			return !day.isBefore(start) && !day.isAfter(end);
		}
	}

	private record Dated(LocalDate day, long id) {
	}

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ClinicClock clock;

	public ReportService(JdbcClient jdbc, CurrentUser currentUser, ClinicClock clock) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public ReportsData summary(LocalDate fromParam, LocalDate toParam, Long requestedDoctor) {
		LocalDate today = clock.today();
		LocalDate from = fromParam != null ? fromParam : (toParam != null ? toParam : today);
		LocalDate to = toParam != null ? toParam : today;
		if (to.isBefore(from)) {
			throw ApiException.badRequest("INVALID_RANGE", "The end date is before the start date.");
		}
		if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
			throw ApiException.unprocessable("RANGE_TOO_LARGE", "Choose a range of " + MAX_DAYS + " days or fewer.");
		}
		long clinicId = currentUser.clinicId();
		Long doctorId = currentUser.role() == Role.DOCTOR ? Long.valueOf(currentUser.id()) : requestedDoctor;
		List<Bucket> buckets = buckets(from, to);

		Map<String, Object> p = new HashMap<>();
		p.put("c", clinicId);
		p.put("from", from);
		p.put("to", to);
		p.put("today", today);
		p.put("tz", clock.zone().getId());
		p.put("doctorId", doctorId);
		String consultDoctor = doctorId == null ? "" : " and c.doctor_id = :doctorId";
		String invoiceDoctor = doctorId == null ? "" : " and i.doctor_id = :doctorId";
		String apptDoctor = doctorId == null ? "" : " and a.doctor_id = :doctorId";
		if (doctorId == null) {
			p.remove("doctorId");
		}

		// ---------- patients & doctors (from consultations) ----------
		record Consult(LocalDate day, long patientId, long doctorId, boolean followUp) {
		}
		List<Consult> consultations = jdbc.sql("""
				select v.queue_date as day, c.patient_id, c.doctor_id,
				       exists (select 1 from follow_up f where f.consultation_id = c.id) as follow_up
				from consultation c join visit v on v.id = c.visit_id
				where c.clinic_id = :c and v.queue_date between :from and :to""" + consultDoctor)
				.params(p).query((rs, i) -> new Consult(rs.getObject("day", LocalDate.class), rs.getLong("patient_id"),
						rs.getLong("doctor_id"), rs.getBoolean("follow_up"))).list();
		Set<Long> seenIds = new HashSet<>();
		consultations.forEach(c -> seenIds.add(c.patientId()));
		long newCount = seenIds.isEmpty() ? 0 : jdbc.sql("""
				select count(*) from patient where clinic_id = :c and id in (:ids)
				  and (registered_at at time zone :tz)::date between :from and :to""")
				.params(p).param("ids", new ArrayList<>(seenIds)).query(Long.class).single();
		long registered = jdbc.sql("""
				select count(*) from patient where clinic_id = :c
				  and (registered_at at time zone :tz)::date between :from and :to""")
				.params(p).query(Long.class).single();
		PatientsReport patients = new PatientsReport(seenIds.size(), newCount, seenIds.size() - newCount, registered,
				uniqueSeries(buckets, consultations.stream().map(c -> new Dated(c.day(), c.patientId())).toList()));

		// ---------- appointments ----------
		record Appt(LocalDate day, long doctorId, String status, String visitStatus) {
		}
		List<Appt> appointments = jdbc.sql("""
				select a.appt_date as day, a.doctor_id, a.status, v.status as visit_status
				from appointment a
				left join lateral (select status from visit where appointment_id = a.id order by id desc limit 1) v on true
				where a.clinic_id = :c and a.status <> 'rescheduled' and a.appt_date between :from and :to""" + apptDoctor)
				.params(p).query((rs, i) -> new Appt(rs.getObject("day", LocalDate.class), rs.getLong("doctor_id"),
						rs.getString("status"), rs.getString("visit_status"))).list();
		List<Appt> due = appointments.stream().filter(a -> a.day().isBefore(today)).toList();
		long completed = due.stream().filter(a -> "checked-in".equals(a.status()) && "completed".equals(a.visitStatus())).count();
		long cancelled = due.stream().filter(a -> "cancelled".equals(a.status())).count();
		long noShow = due.stream().filter(a -> "no-show".equals(a.status())).count();
		AppointmentsReport appointmentsReport = new AppointmentsReport(due.size(), completed, cancelled, noShow,
				appointments.size() - due.size(), rate(completed, due.size()), rate(noShow, due.size()),
				countSeries(buckets, appointments.stream().map(Appt::day).toList()));

		// ---------- queue (clinic-wide, as in the frontend) ----------
		QueueReport queue = jdbc.sql("""
				select count(*) as tokens_issued,
				       count(*) filter (where status = 'completed') as patients_served,
				       count(*) filter (where status in ('cancelled','skipped')) as cancelled_or_skipped
				from visit where clinic_id = :c and queue_date between :from and :to""")
				.params(p).query(QueueReport.class).single();

		// ---------- money ----------
		record Pay(LocalDate day, String method, BigDecimal amount) {
		}
		List<Pay> payments = jdbc.sql("""
				select (pm.paid_at at time zone :tz)::date as day, pm.method, pm.amount
				from payment pm join invoice i on i.id = pm.invoice_id
				where pm.clinic_id = :c and not i.voided
				  and (pm.paid_at at time zone :tz)::date between :from and :to""" + invoiceDoctor)
				.params(p).query((rs, i) -> new Pay(rs.getObject("day", LocalDate.class), rs.getString("method"),
						rs.getBigDecimal("amount"))).list();
		BigDecimal collected = payments.stream().map(Pay::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
		Map<String, BigDecimal> byMethod = new LinkedHashMap<>();
		payments.forEach(x -> byMethod.merge(x.method(), x.amount(), BigDecimal::add));

		record Inv(long doctorId, String description, BigDecimal total, BigDecimal paid) {
		}
		List<Inv> invoices = jdbc.sql("""
				select coalesce(i.doctor_id, 0) as doctor_id, coalesce(i.description, sf.name, 'Consultation') as description,
				       i.total, i.paid
				from invoice_summary i left join service_fee sf on sf.id = i.service_id
				where i.clinic_id = :c and not i.voided
				  and (i.issued_at at time zone :tz)::date between :from and :to""" + invoiceDoctor)
				.params(p).query((rs, i) -> new Inv(rs.getLong("doctor_id"), rs.getString("description"),
						rs.getBigDecimal("total"), rs.getBigDecimal("paid"))).list();
		BigDecimal billed = invoices.stream().map(Inv::total).reduce(BigDecimal.ZERO, BigDecimal::add);

		BigDecimal outstanding = jdbc.sql("""
				select coalesce(sum(i.balance), 0) from invoice_summary i
				where i.clinic_id = :c and not i.voided and i.balance > 0""" + invoiceDoctor)
				.params(p).query(BigDecimal.class).single();
		List<OutstandingInvoice> outstandingInvoices = jdbc.sql("""
				select i.id, i.invoice_no, i.patient_id, pt.name as patient_name, d.full_name as doctor_name,
				       (i.issued_at at time zone :tz)::date as issued_on, i.total, i.paid, i.balance
				from invoice_summary i join patient pt on pt.id = i.patient_id left join app_user d on d.id = i.doctor_id
				where i.clinic_id = :c and not i.voided and i.balance > 0""" + invoiceDoctor + """

				order by i.balance desc, i.id limit 200""")
				.params(p).query((rs, i) -> new OutstandingInvoice(rs.getLong("id"),
						"INV-" + String.format("%05d", rs.getLong("invoice_no")), rs.getLong("patient_id"),
						rs.getString("patient_name"), rs.getString("doctor_name"), rs.getObject("issued_on", LocalDate.class),
						rs.getBigDecimal("total"), rs.getBigDecimal("paid"), rs.getBigDecimal("balance"))).list();

		FinancialReport financial = new FinancialReport(whole(billed), whole(collected), whole(outstanding),
				byMethod.entrySet().stream().map(e -> new MethodAmount(e.getKey(), whole(e.getValue()))).toList(),
				moneySeries(buckets, payments.stream().map(x -> Map.entry(x.day(), x.amount())).toList()));

		Map<String, long[]> serviceMap = new LinkedHashMap<>();
		Map<String, BigDecimal> serviceBilled = new HashMap<>();
		invoices.forEach(inv -> {
			serviceMap.computeIfAbsent(inv.description(), k -> new long[1])[0]++;
			serviceBilled.merge(inv.description(), inv.total(), BigDecimal::add);
		});
		List<ServiceRow> services = serviceMap.entrySet().stream()
				.map(e -> new ServiceRow(e.getKey(), e.getValue()[0], whole(serviceBilled.get(e.getKey()))))
				.sorted(Comparator.comparing(ServiceRow::billed).reversed().thenComparing(ServiceRow::name)).toList();

		// ---------- doctors ----------
		List<DoctorRow> doctorRows = new ArrayList<>();
		var doctors = jdbc.sql("select id, full_name from app_user where clinic_id = :c and role = 'doctor' and active"
				+ (doctorId == null ? "" : " and id = :doctorId") + " order by full_name")
				.params(p).query((rs, i) -> Map.entry(rs.getLong("id"), rs.getString("full_name"))).list();
		for (var doctor : doctors) {
			long id = doctor.getKey();
			List<Consult> mine = consultations.stream().filter(c -> c.doctorId() == id).toList();
			BigDecimal collection = invoices.stream().filter(inv -> inv.doctorId() == id).map(Inv::paid)
					.reduce(BigDecimal.ZERO, BigDecimal::add);
			doctorRows.add(new DoctorRow(id, doctor.getValue(), mine.stream().map(Consult::patientId).distinct().count(),
					mine.size(), appointments.stream().filter(a -> a.doctorId() == id).count(),
					mine.stream().filter(Consult::followUp).count(), whole(collection)));
		}

		// ---------- follow-ups ----------
		record Fu(long id, long patientId, String patientName, String doctorName, LocalDate effective, String status,
				LocalDate visitDate) {
		}
		List<Fu> followUps = jdbc.sql("""
				select f.id, f.patient_id, pt.name as patient_name, d.full_name as doctor_name,
				       coalesce(f.override_due_date, f.due_date) as effective, f.status, v.queue_date as visit_date
				from follow_up f
				join consultation c on c.id = f.consultation_id
				join visit v on v.id = c.visit_id
				join patient pt on pt.id = f.patient_id
				join app_user d on d.id = c.doctor_id
				where f.clinic_id = :c""" + consultDoctor)
				.params(p).query((rs, i) -> new Fu(rs.getLong("id"), rs.getLong("patient_id"), rs.getString("patient_name"),
						rs.getString("doctor_name"), rs.getObject("effective", LocalDate.class), rs.getString("status"),
						rs.getObject("visit_date", LocalDate.class))).list();
		long created = followUps.stream().filter(f -> !f.visitDate().isBefore(from) && !f.visitDate().isAfter(to)).count();
		long fuCompleted = followUps.stream().filter(f -> "completed".equals(f.status())).count();
		long fuCancelled = followUps.stream().filter(f -> "cancelled".equals(f.status())).count();
		List<Fu> open = followUps.stream().filter(f -> f.status() == null).toList();
		long dueToday = open.stream().filter(f -> f.effective().isEqual(today)).count();
		long overdue = open.stream().filter(f -> f.effective().isBefore(today)).count();
		long upcoming = open.stream().filter(f -> f.effective().isAfter(today)).count();
		List<OverdueFollowUp> overdueList = open.stream().filter(f -> f.effective().isBefore(today))
				.map(f -> new OverdueFollowUp(f.id(), f.patientId(), f.patientName(), f.doctorName(), f.effective(),
						ChronoUnit.DAYS.between(f.effective(), today)))
				.sorted(Comparator.comparingLong(OverdueFollowUp::daysOverdue).reversed()
						.thenComparing(OverdueFollowUp::patientName)).toList();
		long becameDue = overdue + dueToday + fuCompleted + fuCancelled;
		FollowUpsReport followUpsReport = new FollowUpsReport(created, dueToday, overdue, upcoming, fuCompleted,
				fuCancelled, rate(fuCompleted, becameDue), overdueList);

		return new ReportsData(from, to, doctorId, patients, appointmentsReport, queue, doctorRows, financial,
				followUpsReport, services, outstandingInvoices);
	}

	// ----- helpers -----

	private static Double rate(long part, long whole) {
		return whole > 0 ? part * 100.0 / whole : null;
	}

	/** Whole rupees, like the screens show. */
	private static BigDecimal whole(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value.setScale(0, RoundingMode.HALF_UP);
	}

	/**
	 * Daily slices for ranges up to 31 days, weekly up to 180, monthly beyond - so a trend chart stays
	 * readable whatever range is chosen (the same thresholds the screen has always used).
	 */
	static List<Bucket> buckets(LocalDate from, LocalDate to) {
		long days = ChronoUnit.DAYS.between(from, to) + 1;
		List<Bucket> buckets = new ArrayList<>();
		if (days <= 31) {
			DateTimeFormatter label = DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH);
			for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
				buckets.add(new Bucket(label.format(d), d, d));
			}
		}
		else if (days <= 180) {
			int week = 1;
			for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(7)) {
				LocalDate end = d.plusDays(6).isAfter(to) ? to : d.plusDays(6);
				buckets.add(new Bucket("Week " + week++, d, end));
			}
		}
		else {
			DateTimeFormatter label = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH);
			for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
				LocalDate start = m.atDay(1).isBefore(from) ? from : m.atDay(1);
				LocalDate end = m.atEndOfMonth().isAfter(to) ? to : m.atEndOfMonth();
				buckets.add(new Bucket(label.format(m.atDay(1)), start, end));
			}
		}
		return buckets;
	}

	private static CountSeries countSeries(List<Bucket> buckets, List<LocalDate> days) {
		return new CountSeries(buckets.stream().map(Bucket::label).toList(),
				buckets.stream().map(b -> days.stream().filter(b::contains).count()).toList());
	}

	private static CountSeries uniqueSeries(List<Bucket> buckets, List<Dated> items) {
		return new CountSeries(buckets.stream().map(Bucket::label).toList(),
				buckets.stream().map(b -> items.stream().filter(i -> b.contains(i.day())).map(Dated::id).distinct().count())
						.toList());
	}

	private static MoneySeries moneySeries(List<Bucket> buckets, List<Map.Entry<LocalDate, BigDecimal>> items) {
		return new MoneySeries(buckets.stream().map(Bucket::label).toList(),
				buckets.stream().map(b -> whole(items.stream().filter(i -> b.contains(i.getKey()))
						.map(Map.Entry::getValue).reduce(BigDecimal.ZERO, BigDecimal::add))).toList());
	}
}
