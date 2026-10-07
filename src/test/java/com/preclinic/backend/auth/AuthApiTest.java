package com.preclinic.backend.auth;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.preclinic.backend.ApiIntegrationTest;
import com.preclinic.backend.user.AppUser;
import com.preclinic.backend.user.Role;

class AuthApiTest extends ApiIntegrationTest {

	AppUser doctor;
	AppUser assistant;

	@BeforeEach
	void createUsers() {
		doctor = newUser("doc@test.local", "Dr. Test", Role.DOCTOR);
		assistant = newUser("asst@test.local", "Ayesha Assistant", Role.ASSISTANT);
	}

	private String loginBody(String email, String password) {
		return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
	}

	// --- login ---

	@Test
	void loginReturnsATokenAndTheUser() throws Exception {
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(loginBody("doc@test.local", PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token", not(emptyString())))
				.andExpect(jsonPath("$.expiresAt").exists())
				.andExpect(jsonPath("$.user.id").value(doctor.getId()))
				.andExpect(jsonPath("$.user.name").value("Dr. Test"))
				.andExpect(jsonPath("$.user.role").value("doctor"))
				.andExpect(jsonPath("$.user.clinic").value("Clinstra Family Clinic"))
				.andExpect(jsonPath("$.user.email").value("doc@test.local"));
	}

	@Test
	void loginIgnoresEmailCase() throws Exception {
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(loginBody("  DOC@Test.Local ", PASSWORD)))
				.andExpect(status().isOk());
	}

	@Test
	void wrongPasswordAndUnknownEmailLookIdentical() throws Exception {
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(loginBody("doc@test.local", "wrong-password")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
				.andExpect(jsonPath("$.detail").value("Incorrect email or password."));
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(loginBody("nobody@test.local", PASSWORD)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
				.andExpect(jsonPath("$.detail").value("Incorrect email or password."));
	}

	@Test
	void inactiveUserCannotLogIn() throws Exception {
		doctor.setActive(false);
		users.saveAndFlush(doctor);
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(loginBody("doc@test.local", PASSWORD)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
	}

	@Test
	void loginValidatesTheBody() throws Exception {
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors", hasSize(2)));
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("not json"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
	}

	// --- token protection ---

	@Test
	void protectedEndpointsRejectMissingOrBadTokens() throws Exception {
		mvc.perform(get("/api/v1/auth/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.token"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void meReturnsTheCurrentUser() throws Exception {
		mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(assistant)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(assistant.getId()))
				.andExpect(jsonPath("$.role").value("assistant"))
				.andExpect(jsonPath("$.email").value("asst@test.local"));
	}

	@Test
	void aTokenStopsWorkingOnceTheAccountIsDeactivated() throws Exception {
		String token = bearer(doctor);
		doctor.setActive(false);
		users.saveAndFlush(doctor);
		mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isUnauthorized());
	}

	// --- change password ---

	@Test
	void changePasswordRequiresTheCurrentOne() throws Exception {
		mvc.perform(post("/api/v1/auth/change-password").header(HttpHeaders.AUTHORIZATION, bearer(doctor))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"currentPassword\":\"wrong\",\"newPassword\":\"brand-new-1\"}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"));
	}

	@Test
	void changePasswordRejectsAShortNewPassword() throws Exception {
		mvc.perform(post("/api/v1/auth/change-password").header(HttpHeaders.AUTHORIZATION, bearer(doctor))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"123\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void changePasswordSwitchesWhichPasswordWorks() throws Exception {
		mvc.perform(post("/api/v1/auth/change-password").header(HttpHeaders.AUTHORIZATION, bearer(doctor))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"brand-new-1\"}"))
				.andExpect(status().isNoContent());

		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(loginBody("doc@test.local", PASSWORD)))
				.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(loginBody("doc@test.local", "brand-new-1")))
				.andExpect(status().isOk());
	}

	// --- error format & CORS ---

	@Test
	void unknownPathIs404NotAServerError() throws Exception {
		mvc.perform(get("/api/v1/does-not-exist").header(HttpHeaders.AUTHORIZATION, bearer(doctor)))
				.andExpect(status().isNotFound());
	}

	@Test
	void wrongMethodIs405NotAServerError() throws Exception {
		mvc.perform(get("/api/v1/auth/login").header(HttpHeaders.AUTHORIZATION, bearer(doctor)))
				.andExpect(status().isMethodNotAllowed());
	}

	@Test
	void theAngularDevOriginPassesCorsPreflight() throws Exception {
		mvc.perform(options("/api/v1/auth/login")
				.header(HttpHeaders.ORIGIN, "http://localhost:4200")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200"));
	}

	@Test
	void otherOriginsAreRefused() throws Exception {
		mvc.perform(options("/api/v1/auth/login")
				.header(HttpHeaders.ORIGIN, "http://evil.example")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
				.andExpect(status().isForbidden());
	}

	@Test
	void healthProbeNeedsNoToken() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}
}
