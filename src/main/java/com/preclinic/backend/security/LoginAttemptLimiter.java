package com.preclinic.backend.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.preclinic.backend.common.ApiException;
import com.preclinic.backend.common.ClinicClock;

/**
 * Slows password guessing: after {@code maxAttempts} wrong passwords for the same account from the
 * same address, further sign-ins are refused for a lock-out period (even with the right password).
 * A success clears the count. Counting per account AND address means someone elsewhere cannot lock
 * the doctor out of their own clinic by guessing their email.
 *
 * State is in memory: it resets on restart and is per server instance, which is the right weight for a
 * clinic-sized deployment (a shared store such as Redis would be the next step for several instances).
 */
@Component
public class LoginAttemptLimiter {

	private record State(int failures, Instant lockedUntil) {
	}

	private final Map<String, State> states = new ConcurrentHashMap<>();
	private final ClinicClock clock;
	private final int maxAttempts;
	private final Duration lockout;

	public LoginAttemptLimiter(ClinicClock clock, @Value("${clinstra.security.login-max-attempts:5}") int maxAttempts,
			@Value("${clinstra.security.login-lockout-minutes:15}") long lockoutMinutes) {
		this.clock = clock;
		this.maxAttempts = maxAttempts;
		this.lockout = Duration.ofMinutes(lockoutMinutes);
	}

	static String key(String clientAddress, String email) {
		return clientAddress + "|" + email.trim().toLowerCase();
	}

	/** Throws 429 while the key is locked out. */
	public void checkAllowed(String clientAddress, String email) {
		State state = states.get(key(clientAddress, email));
		Instant now = clock.now();
		if (state != null && state.lockedUntil() != null && state.lockedUntil().isAfter(now)) {
			long minutes = Math.max(1, Duration.between(now, state.lockedUntil()).toMinutes() + 1);
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
					"Too many failed sign-in attempts. Try again in " + minutes + " minute" + (minutes == 1 ? "" : "s") + ".");
		}
	}

	public void recordFailure(String clientAddress, String email) {
		Instant now = clock.now();
		states.compute(key(clientAddress, email), (k, old) -> {
			// a lock-out that has run out starts a fresh count
			int failures = old == null || (old.lockedUntil() != null && !old.lockedUntil().isAfter(now)) ? 1
					: old.failures() + 1;
			return new State(failures, failures >= maxAttempts ? now.plus(lockout) : null);
		});
	}

	public void recordSuccess(String clientAddress, String email) {
		states.remove(key(clientAddress, email));
	}

	/** For tests. */
	public void reset() {
		states.clear();
	}
}
