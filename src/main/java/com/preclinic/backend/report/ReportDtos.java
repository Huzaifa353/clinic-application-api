package com.preclinic.backend.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class ReportDtos {

	private ReportDtos() {
	}

	/** Chart series: one label and one value per day, week or month depending on the span of the range. */
	public record CountSeries(List<String> labels, List<Long> values) {
	}

	public record MoneySeries(List<String> labels, List<BigDecimal> values) {
	}

	public record PatientsReport(long seen, long newCount, long returning, long registered, CountSeries trend) {
	}

	/**
	 * {@code due} = appointments whose day has passed; {@code upcoming} = the rest of the range.
	 * {@code completed} = patients actually seen (the checked-in appointment's visit finished).
	 * Rates are percentages of {@code due}, null when nothing was due.
	 */
	public record AppointmentsReport(long due, long completed, long cancelled, long noShow, long upcoming,
			Double completionRate, Double noShowRate, CountSeries trend) {
	}

	public record QueueReport(long tokensIssued, long patientsServed, long cancelledOrSkipped) {
	}

	public record DoctorRow(long doctorId, String name, long patientsSeen, long consultations, long appointments,
			long followUps, BigDecimal collection) {
	}

	public record MethodAmount(String method, BigDecimal amount) {
	}

	/** {@code outstanding} is today's unpaid balance (not limited to the range). */
	public record FinancialReport(BigDecimal billed, BigDecimal collected, BigDecimal outstanding,
			List<MethodAmount> byMethod, MoneySeries trend) {
	}

	public record OverdueFollowUp(long followUpId, long patientId, String patientName, String doctorName,
			LocalDate dueDate, long daysOverdue) {
	}

	/**
	 * Created is limited to the range; the state counts are current (all open follow-ups as of today).
	 * {@code completionRate} = completed / (overdue + due today + completed + cancelled).
	 */
	public record FollowUpsReport(long created, long dueToday, long overdue, long upcoming, long completed,
			long cancelled, Double completionRate, List<OverdueFollowUp> overdueList) {
	}

	public record ServiceRow(String name, long count, BigDecimal billed) {
	}

	public record OutstandingInvoice(long invoiceId, String invoiceDisplayId, long patientId, String patientName,
			String doctorName, LocalDate issuedOn, BigDecimal total, BigDecimal paid, BigDecimal balance) {
	}

	public record ReportsData(LocalDate from, LocalDate to, Long doctorId, PatientsReport patients,
			AppointmentsReport appointments, QueueReport queue, List<DoctorRow> doctors, FinancialReport financial,
			FollowUpsReport followUps, List<ServiceRow> services, List<OutstandingInvoice> outstandingInvoices) {
	}
}
