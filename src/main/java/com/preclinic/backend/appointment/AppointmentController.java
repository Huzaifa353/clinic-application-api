package com.preclinic.backend.appointment;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.preclinic.backend.appointment.AppointmentDtos.AppointmentDto;
import com.preclinic.backend.appointment.AppointmentDtos.AppointmentStats;
import com.preclinic.backend.appointment.AppointmentDtos.CancelRequest;
import com.preclinic.backend.appointment.AppointmentDtos.ConflictsDto;
import com.preclinic.backend.appointment.AppointmentDtos.CreateAppointmentRequest;
import com.preclinic.backend.appointment.AppointmentDtos.RescheduleRequest;
import com.preclinic.backend.common.Api;
import com.preclinic.backend.common.PageResult;

import jakarta.validation.Valid;

/** Appointments. Both roles book and manage them (the frontend offers "Book Appointment" to both). */
@RestController
@RequestMapping(Api.V1 + "/appointments")
public class AppointmentController {

	private final AppointmentService appointments;

	public AppointmentController(AppointmentService appointments) {
		this.appointments = appointments;
	}

	/** Day view ({@code date}), calendar range ({@code from}/{@code to}) or a patient's history. */
	@GetMapping
	public PageResult<AppointmentDto> list(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String type,
			@RequestParam(required = false) Long doctorId,
			@RequestParam(required = false) Long patientId,
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String dir,
			@RequestParam(defaultValue = "0") int skip,
			@RequestParam(defaultValue = "50") int limit) {
		List<String> statuses = status == null || status.isBlank() ? List.of()
				: Arrays.stream(status.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
		return appointments.list(new AppointmentService.Query(date, from, to, statuses, type, doctorId, patientId, q,
				dir, Math.max(skip, 0), Math.min(Math.max(limit, 1), 500)));
	}

	@GetMapping("/stats")
	public AppointmentStats stats(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return appointments.stats(date);
	}

	/** Pre-checks used to warn before booking: the same patient that day, and the doctor's slot. */
	@GetMapping("/conflicts")
	public ConflictsDto conflicts(@RequestParam long patientId, @RequestParam(required = false) Long doctorId,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime time) {
		return appointments.conflicts(patientId, doctorId, date, time);
	}

	@GetMapping("/{id}")
	public AppointmentDto get(@PathVariable long id) {
		return appointments.get(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public AppointmentDto create(@Valid @RequestBody CreateAppointmentRequest request,
			@RequestParam(defaultValue = "false") boolean force) {
		return appointments.create(request, force);
	}

	@PostMapping("/{id}/arrive")
	public AppointmentDto arrive(@PathVariable long id) {
		return appointments.markArrived(id);
	}

	@PostMapping("/{id}/cancel")
	public AppointmentDto cancel(@PathVariable long id, @Valid @RequestBody(required = false) CancelRequest request) {
		return appointments.cancel(id, request == null ? null : request.reason());
	}

	@PostMapping("/{id}/no-show")
	public AppointmentDto noShow(@PathVariable long id) {
		return appointments.markNoShow(id);
	}

	@PostMapping("/{id}/reschedule")
	public AppointmentDto reschedule(@PathVariable long id, @Valid @RequestBody RescheduleRequest request) {
		return appointments.reschedule(id, request);
	}
}
