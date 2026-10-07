package com.preclinic.backend.queue;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.preclinic.backend.common.Api;
import com.preclinic.backend.queue.QueueDtos.IntakeRequest;
import com.preclinic.backend.queue.QueueDtos.OrderRequest;
import com.preclinic.backend.queue.QueueDtos.QueueItemDto;
import com.preclinic.backend.queue.QueueDtos.QueueStats;
import com.preclinic.backend.queue.QueueDtos.StatusRequest;
import com.preclinic.backend.queue.QueueDtos.UrgentRequest;
import com.preclinic.backend.queue.QueueDtos.VitalsDto;

import jakarta.validation.Valid;

/**
 * The daily queue. Both roles read it and move patients through it (call, hold, skip ...). Front-desk
 * work - intake, vitals, urgent flag, reordering, removing - is the assistant's.
 */
@RestController
@RequestMapping(Api.V1 + "/queue")
public class QueueController {

	private final QueueService queue;

	public QueueController(QueueService queue) {
		this.queue = queue;
	}

	/** A day's queue in display order (today by default); also serves the history screen for past dates. */
	@GetMapping
	public List<QueueItemDto> list(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String source,
			@RequestParam(required = false) String q) {
		return queue.list(date, status, source, q);
	}

	@GetMapping("/stats")
	public QueueStats stats(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return queue.stats(date);
	}

	@GetMapping("/{id}")
	public QueueItemDto get(@PathVariable long id) {
		return queue.get(id);
	}

	/** "Add to Queue": token + visit + invoice (+ first payment) + appointment check-in, all or nothing. */
	@PostMapping("/intake")
	@PreAuthorize("hasRole('ASSISTANT')")
	@ResponseStatus(HttpStatus.CREATED)
	public QueueItemDto intake(@Valid @RequestBody IntakeRequest request,
			@RequestParam(defaultValue = "false") boolean force) {
		return queue.intake(request, force);
	}

	@PatchMapping("/{id}/status")
	public QueueItemDto status(@PathVariable long id, @Valid @RequestBody StatusRequest request) {
		return queue.changeStatus(id, request.status());
	}

	/** Calls the first waiting patient to the doctor. */
	@PostMapping("/call-next")
	public QueueItemDto callNext() {
		return queue.callNext();
	}

	@PatchMapping("/{id}/vitals")
	@PreAuthorize("hasRole('ASSISTANT')")
	public QueueItemDto vitals(@PathVariable long id, @Valid @RequestBody VitalsDto request) {
		return queue.updateVitals(id, request);
	}

	@PatchMapping("/{id}/urgent")
	@PreAuthorize("hasRole('ASSISTANT')")
	public QueueItemDto urgent(@PathVariable long id, @Valid @RequestBody UrgentRequest request) {
		return queue.setUrgent(id, request.urgent());
	}

	@PutMapping("/order")
	@PreAuthorize("hasRole('ASSISTANT')")
	public List<QueueItemDto> reorder(@Valid @RequestBody OrderRequest request) {
		return queue.reorder(request.orderedIds());
	}

	/** Takes the patient out of the queue (kept in history as cancelled). */
	@DeleteMapping("/{id}")
	@PreAuthorize("hasRole('ASSISTANT')")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void remove(@PathVariable long id) {
		queue.remove(id);
	}
}
