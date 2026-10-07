package com.preclinic.backend.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class ClinicClockTest {

	private static ClinicClock at(String utcInstant) {
		return new ClinicClock(Clock.fixed(Instant.parse(utcInstant), ZoneOffset.UTC), "Asia/Karachi");
	}

	@Test
	void todayIsTheClinicLocalDateNotTheUtcDate() {
		// 20:30 UTC on the 6th is already 01:30 on the 7th in Karachi (UTC+5).
		assertThat(at("2026-10-06T20:30:00Z").today()).isEqualTo(LocalDate.of(2026, 10, 7));
	}

	@Test
	void justBeforeLocalMidnightIsStillTheSameDay() {
		// 18:59 UTC is 23:59 in Karachi.
		assertThat(at("2026-10-06T18:59:00Z").today()).isEqualTo(LocalDate.of(2026, 10, 6));
		assertThat(at("2026-10-06T19:00:00Z").today()).isEqualTo(LocalDate.of(2026, 10, 7));
	}
}
