package com.preclinic.backend.event;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.preclinic.backend.common.ClinicClock;

/**
 * A browser's EventSource cannot send an Authorization header, and putting the long-lived login token
 * in a URL would leak it into logs. Instead the logged-in app asks for a one-time ticket (valid for
 * seconds) and opens the stream with that.
 */
@Component
public class EventTickets {

	static final Duration VALIDITY = Duration.ofSeconds(30);

	private record Ticket(long clinicId, Instant expiresAt) {
	}

	private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
	private final SecureRandom random = new SecureRandom();
	private final ClinicClock clock;

	public EventTickets(ClinicClock clock) {
		this.clock = clock;
	}

	public String issue(long clinicId) {
		Instant now = clock.now();
		tickets.values().removeIf(t -> t.expiresAt().isBefore(now));
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		tickets.put(ticket, new Ticket(clinicId, now.plus(VALIDITY)));
		return ticket;
	}

	/** Single use: the clinic id the ticket was issued for, or -1 when unknown, used or expired. */
	public long redeem(String ticket) {
		if (ticket == null) {
			return -1;
		}
		Ticket found = tickets.remove(ticket);
		if (found == null || found.expiresAt().isBefore(clock.now())) {
			return -1;
		}
		return found.clinicId();
	}
}
