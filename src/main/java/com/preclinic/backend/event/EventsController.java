package com.preclinic.backend.event;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.preclinic.backend.common.Api;
import com.preclinic.backend.common.ApiException;
import com.preclinic.backend.security.CurrentUser;

/**
 * Live updates. Flow: the logged-in app POSTs {@code /events/ticket}, then opens
 * {@code new EventSource('/api/v1/events?ticket=...')}. Events are named after what changed
 * (queue, appointments, notification, doctor-status, invoice, consultation, follow-up).
 */
@RestController
@RequestMapping(Api.V1 + "/events")
@EnableScheduling
public class EventsController {

	public record TicketDto(String ticket, long expiresInSeconds) {
	}

	private final EventBus bus;
	private final EventTickets tickets;
	private final CurrentUser currentUser;

	public EventsController(EventBus bus, EventTickets tickets, CurrentUser currentUser) {
		this.bus = bus;
		this.tickets = tickets;
		this.currentUser = currentUser;
	}

	@PostMapping("/ticket")
	public TicketDto ticket() {
		return new TicketDto(tickets.issue(currentUser.clinicId()), EventTickets.VALIDITY.toSeconds());
	}

	/** Public route (no Authorization header possible); the one-time ticket is the credential. */
	@GetMapping
	public SseEmitter stream(@RequestParam String ticket) {
		long clinicId = tickets.redeem(ticket);
		if (clinicId < 0) {
			throw ApiException.unauthorized("INVALID_TICKET", "The event ticket is invalid or has expired.");
		}
		return bus.subscribe(clinicId);
	}
}
