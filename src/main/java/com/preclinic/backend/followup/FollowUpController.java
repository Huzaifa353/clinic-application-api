package com.preclinic.backend.followup;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.preclinic.backend.common.Api;
import com.preclinic.backend.common.PageResult;
import com.preclinic.backend.followup.FollowUpDtos.CancelRequest;
import com.preclinic.backend.followup.FollowUpDtos.ContactRequest;
import com.preclinic.backend.followup.FollowUpDtos.FollowUpItemDto;
import com.preclinic.backend.followup.FollowUpDtos.FollowUpStats;
import com.preclinic.backend.followup.FollowUpDtos.RescheduleRequest;
import com.preclinic.backend.followup.FollowUpDtos.ScheduleRequest;

import jakarta.validation.Valid;

/** Follow-ups. Both roles work them (the assistant chases patients, the doctor reviews). */
@RestController
@RequestMapping(Api.V1 + "/follow-ups")
public class FollowUpController {

	private final FollowUpService followUps;

	public FollowUpController(FollowUpService followUps) {
		this.followUps = followUps;
	}

	/** The follow-ups screen: {@code tab} is due, overdue, upcoming, completed (closed) or all. */
	@GetMapping
	public PageResult<FollowUpItemDto> list(
			@RequestParam(defaultValue = "all") String tab,
			@RequestParam(required = false) String q,
			@RequestParam(required = false) Long doctorId,
			@RequestParam(required = false) String contact,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) String sort,
			@RequestParam(required = false) String dir,
			@RequestParam(defaultValue = "0") int skip,
			@RequestParam(defaultValue = "10") int limit) {
		return followUps.list(new FollowUpService.Query(tab, q, doctorId, contact, from, to, sort, dir,
				Math.max(skip, 0), Math.min(Math.max(limit, 1), 200)));
	}

	@GetMapping("/stats")
	public FollowUpStats stats() {
		return followUps.stats();
	}

	/** Open follow-ups due today or overdue: the assistant dashboard's list. */
	@GetMapping("/due")
	public List<FollowUpItemDto> due() {
		return followUps.dueOrOverdue();
	}

	@GetMapping("/{id}")
	public FollowUpItemDto get(@PathVariable long id) {
		return followUps.get(id);
	}

	@PostMapping("/{id}/remind")
	public FollowUpItemDto remind(@PathVariable long id) {
		return followUps.remind(id);
	}

	@PutMapping("/{id}/contact-status")
	public FollowUpItemDto contact(@PathVariable long id, @Valid @RequestBody ContactRequest request) {
		return followUps.setContactStatus(id, request.status());
	}

	@PostMapping("/{id}/reschedule")
	public FollowUpItemDto reschedule(@PathVariable long id, @Valid @RequestBody RescheduleRequest request) {
		return followUps.reschedule(id, request.date());
	}

	@PostMapping("/{id}/complete")
	public FollowUpItemDto complete(@PathVariable long id) {
		return followUps.complete(id);
	}

	@PostMapping("/{id}/cancel")
	public FollowUpItemDto cancel(@PathVariable long id, @Valid @RequestBody(required = false) CancelRequest request) {
		return followUps.cancel(id, request == null ? null : request.reason());
	}

	@PostMapping("/{id}/schedule-appointment")
	public FollowUpItemDto schedule(@PathVariable long id, @Valid @RequestBody ScheduleRequest request,
			@RequestParam(defaultValue = "false") boolean force) {
		return followUps.schedule(id, request, force);
	}
}
