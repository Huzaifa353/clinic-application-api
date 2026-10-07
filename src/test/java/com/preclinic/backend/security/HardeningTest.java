package com.preclinic.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.preclinic.backend.ApiIntegrationTest;
import com.preclinic.backend.common.ApiException;
import com.preclinic.backend.common.ClinicClock;
import com.preclinic.backend.common.ProductionSafetyCheck;
import com.preclinic.backend.user.AppUser;
import com.preclinic.backend.user.Role;

class HardeningTest extends ApiIntegrationTest {

	AppUser doctor;
	AppUser assistant;

	@BeforeEach
	void setUp() {
		doctor = newUser("doc@test.local", "Dr. Test", Role.DOCTOR);
		assistant = newUser("asst@test.local", "Ayesha Assistant", Role.ASSISTANT);
	}

	private String login(String email, String password) {
		return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
	}

	// ===== a token dies with its account =====

	@Test
	void aDeactivatedUsersTokenStopsWorkingOnEveryEndpoint() throws Exception {
		String token = bearer(assistant);
		mvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isOk());
		assistant.setActive(false);
		users.saveAndFlush(assistant);
		for (String path : new String[] { "/api/v1/users", "/api/v1/queue", "/api/v1/patients", "/api/v1/invoices" }) {
			mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, token))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		}
	}

	@Test
	void aTokenIssuedBeforeARoleChangeIsRefused() throws Exception {
		String asAssistant = bearer(assistant);
		jdbc.sql("update app_user set role = 'doctor' where id = :id").param("id", assistant.getId()).update();
		mvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, asAssistant)).andExpect(status().isUnauthorized());
	}

	@Test
	void anActiveUserIsUnaffected() throws Exception {
		mvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, bearer(doctor))).andExpect(status().isOk());
	}

	// ===== guessing passwords =====

	@Test
	void fiveWrongPasswordsLockTheAccountOutEvenForTheRightPassword() throws Exception {
		for (int i = 0; i < 5; i++) {
			mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
					.content(login("doc@test.local", "wrong-" + i)))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
		}
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(login("doc@test.local", PASSWORD)))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
		// somebody else is not affected
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(login("asst@test.local", PASSWORD)))
				.andExpect(status().isOk());
	}

	@Test
	void aSuccessfulSignInClearsTheCount() throws Exception {
		for (int round = 0; round < 3; round++) {
			for (int i = 0; i < 4; i++) {
				mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content(login("doc@test.local", "wrong")))
						.andExpect(status().isUnauthorized());
			}
			mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(login("doc@test.local", PASSWORD)))
					.andExpect(status().isOk());
		}
	}

	@Test
	void theEmailIsMatchedIgnoringCaseSoVariantsShareOneCount() throws Exception {
		String[] variants = { "doc@test.local", "DOC@test.local", " Doc@Test.local ", "doc@TEST.local", "DOC@TEST.LOCAL" };
		for (String email : variants) {
			mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(login(email, "wrong")))
					.andExpect(status().isUnauthorized());
		}
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(login("doc@test.local", PASSWORD)))
				.andExpect(status().isTooManyRequests());
	}

	/** A clock the test can move. */
	static class MovableClock extends Clock {
		Instant now = Instant.parse("2026-10-07T08:00:00Z");

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	@Test
	void theLockoutEndsAfterTheConfiguredTime() {
		MovableClock clock = new MovableClock();
		LoginAttemptLimiter limiter = new LoginAttemptLimiter(new ClinicClock(clock, "Asia/Karachi"), 3, 15);
		for (int i = 0; i < 3; i++) {
			limiter.checkAllowed("1.2.3.4", "a@b.c");
			limiter.recordFailure("1.2.3.4", "a@b.c");
		}
		assertThatThrownBy(() -> limiter.checkAllowed("1.2.3.4", "a@b.c")).isInstanceOf(ApiException.class)
				.hasMessageContaining("minute");
		clock.now = clock.now.plusSeconds(14 * 60);
		assertThatThrownBy(() -> limiter.checkAllowed("1.2.3.4", "a@b.c")).isInstanceOf(ApiException.class);
		clock.now = clock.now.plusSeconds(2 * 60);
		limiter.checkAllowed("1.2.3.4", "a@b.c"); // allowed again
		// and after the lockout a single new failure does not lock again immediately
		limiter.recordFailure("1.2.3.4", "a@b.c");
		limiter.checkAllowed("1.2.3.4", "a@b.c");
		// another address has its own count
		limiter.checkAllowed("9.9.9.9", "a@b.c");
	}

	// ===== password policy =====

	@Test
	void newPasswordsNeedLengthALetterAndANumber() throws Exception {
		for (String weak : new String[] { "abc123", "12345678", "abcdefgh", "        1a" }) {
			String body = "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + weak + "\"}";
			var result = mvc.perform(post("/api/v1/auth/change-password").header(HttpHeaders.AUTHORIZATION, bearer(doctor))
					.contentType(MediaType.APPLICATION_JSON).content(body));
			if (weak.equals("        1a")) {
				// spaces count as characters; length and letter/number rules are what matter
				result.andExpect(status().isNoContent());
			}
			else {
				result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
			}
		}
		mvc.perform(post("/api/v1/auth/change-password").header(HttpHeaders.AUTHORIZATION, bearer(assistant))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"clinic2026\"}"))
				.andExpect(status().isNoContent());
	}

	// ===== audit log =====

	@Test
	void theDoctorReadsTheAuditTrailAndTheAssistantCannot() throws Exception {
		long patient = data.patient("Audited Patient", "03000000201");
		mvc.perform(post("/api/v1/invoices").header(HttpHeaders.AUTHORIZATION, bearer(assistant))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"patientId\":" + patient + ",\"consultationFee\":1000,\"amountPaid\":400}")).andExpect(status().isCreated());
		long invoice = jdbc.sql("select id from invoice").query(Long.class).single();
		mvc.perform(post("/api/v1/invoices/" + invoice + "/void").header(HttpHeaders.AUTHORIZATION, bearer(doctor))
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"duplicate\"}")).andExpect(status().isOk());

		String asDoctor = bearer(doctor);
		mvc.perform(get("/api/v1/audit-log").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalData").value(2))
				.andExpect(jsonPath("$.data[0].action").value("invoice.void"))
				.andExpect(jsonPath("$.data[0].userName").value("Dr. Test"))
				.andExpect(jsonPath("$.data[0].entity").value("invoice"))
				.andExpect(jsonPath("$.data[0].entityId").value(invoice))
				.andExpect(jsonPath("$.data[0].detail").value(org.hamcrest.Matchers.containsString("duplicate")))
				.andExpect(jsonPath("$.data[1].action").value("invoice.create"))
				.andExpect(jsonPath("$.data[1].userName").value("Ayesha Assistant"));
		mvc.perform(get("/api/v1/audit-log").param("action", "invoice.void").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.totalData").value(1));
		mvc.perform(get("/api/v1/audit-log").param("action", "invoice").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.totalData").value(2));
		mvc.perform(get("/api/v1/audit-log").param("userId", String.valueOf(assistant.getId())).header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.data", hasSize(1)));
		mvc.perform(get("/api/v1/audit-log").param("entity", "invoice").param("entityId", String.valueOf(invoice))
				.param("limit", "1").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.totalData").value(2))
				.andExpect(jsonPath("$.data", hasSize(1)));
		mvc.perform(get("/api/v1/audit-log").param("entity", "queue").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.totalData").value(0));
		mvc.perform(get("/api/v1/audit-log").header(HttpHeaders.AUTHORIZATION, bearer(assistant)))
				.andExpect(status().isForbidden());

		String asStranger = bearer(newUser(otherClinic().getId(), "stranger@other.local", "Stranger", Role.DOCTOR));
		mvc.perform(get("/api/v1/audit-log").header(HttpHeaders.AUTHORIZATION, asStranger))
				.andExpect(jsonPath("$.totalData").value(0));
	}

	// ===== production safety =====

	@Test
	void productionRefusesDevelopmentDefaults() {
		String strongJwt = "x".repeat(64);
		String strongKey = "y".repeat(40);
		new ProductionSafetyCheck(strongJwt, strongKey, false).verify(); // fine

		assertThatThrownBy(() -> new ProductionSafetyCheck("dev-only-secret-change-me-0123456789abcdef", strongKey, false).verify())
				.hasMessageContaining("JWT_SECRET");
		assertThatThrownBy(() -> new ProductionSafetyCheck("short", strongKey, false).verify())
				.hasMessageContaining("JWT_SECRET");
		assertThatThrownBy(() -> new ProductionSafetyCheck(strongJwt, "dev-only-secrets-key-change-me-in-production", false).verify())
				.hasMessageContaining("SECRETS_KEY");
		assertThatThrownBy(() -> new ProductionSafetyCheck(strongJwt, "tooshort", false).verify())
				.hasMessageContaining("SECRETS_KEY");
		assertThatThrownBy(() -> new ProductionSafetyCheck(strongJwt, strongKey, true).verify())
				.hasMessageContaining("dev-seed");
		assertThat(true).isTrue();
	}
}
