package com.preclinic.backend.report;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.preclinic.backend.common.Api;
import com.preclinic.backend.report.ReportDtos.ReportsData;

@RestController
@RequestMapping(Api.V1 + "/reports")
public class ReportController {

	private final ReportService reports;

	public ReportController(ReportService reports) {
		this.reports = reports;
	}

	/**
	 * Everything the Reports & Analytics screen shows for the range (clinic-local days, inclusive;
	 * today when omitted). A doctor always gets their own figures whatever {@code doctorId} says.
	 */
	@GetMapping("/summary")
	public ReportsData summary(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Long doctorId) {
		return reports.summary(from, to, doctorId);
	}
}
