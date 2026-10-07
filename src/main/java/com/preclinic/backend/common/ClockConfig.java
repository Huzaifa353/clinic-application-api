package com.preclinic.backend.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ClockConfig {

	/** UTC system clock; tests replace it to control time. */
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
}
