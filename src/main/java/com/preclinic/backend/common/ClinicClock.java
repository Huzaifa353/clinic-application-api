package com.preclinic.backend.common;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The single source of "now" and "today". The queue day, token numbering and follow-up due state all
 * mean the clinic-local calendar day, never the server's own zone.
 */
@Component
public class ClinicClock {

	private final Clock clock;
	private final ZoneId zone;

	public ClinicClock(Clock clock, @Value("${clinstra.timezone:Asia/Karachi}") String timezone) {
		this.clock = clock;
		this.zone = ZoneId.of(timezone);
	}

	public Instant now() {
		return clock.instant();
	}

	/** Today's date in the clinic's time zone. */
	public LocalDate today() {
		return LocalDate.ofInstant(clock.instant(), zone);
	}

	public ZoneId zone() {
		return zone;
	}
}
