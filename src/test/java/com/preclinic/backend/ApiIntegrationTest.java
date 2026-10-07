package com.preclinic.backend;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.preclinic.backend.common.ClinicClock;
import com.preclinic.backend.security.JwtService;
import com.preclinic.backend.security.LoginAttemptLimiter;
import com.preclinic.backend.user.AppUser;
import com.preclinic.backend.user.AppUserRepository;
import com.preclinic.backend.user.Clinic;
import com.preclinic.backend.user.ClinicRepository;
import com.preclinic.backend.user.Role;

/**
 * Base for HTTP-level tests: a real Spring context and PostgreSQL ({@code clinstra_test}), with every
 * test rolled back so nothing leaks between tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public abstract class ApiIntegrationTest {

	public static final String PASSWORD = "secret1";

	@Autowired
	protected MockMvc mvc;
	@Autowired
	protected AppUserRepository users;
	@Autowired
	protected ClinicRepository clinics;
	@Autowired
	protected PasswordEncoder encoder;
	@Autowired
	protected JwtService jwt;
	@Autowired
	protected JdbcClient jdbc;
	@Autowired
	protected ClinicClock clock;
	@Autowired
	protected LoginAttemptLimiter loginLimiter;

	/** Row factory for the default clinic. */
	protected Fixtures data;

	@BeforeEach
	void createFixtures() {
		loginLimiter.reset();
		data = new Fixtures(jdbc, defaultClinic().getId());
	}

	protected Clinic defaultClinic() {
		return clinics.findFirstByOrderByIdAsc().orElseThrow();
	}

	/** A second clinic, to prove one clinic can never see another's data. */
	protected Clinic otherClinic() {
		Clinic clinic = new Clinic();
		clinic.setName("Other Clinic");
		clinic.setTimezone("Asia/Karachi");
		return clinics.saveAndFlush(clinic);
	}

	protected AppUser newUser(String email, String name, Role role) {
		return newUser(defaultClinic().getId(), email, name, role);
	}

	protected AppUser newUser(long clinicId, String email, String name, Role role) {
		AppUser user = new AppUser();
		user.setClinicId(clinicId);
		user.setEmail(email);
		user.setFullName(name);
		user.setRole(role);
		user.setPasswordHash(encoder.encode(PASSWORD));
		return users.save(user);
	}

	/** An Authorization header value for the user, signed exactly like a real login token. */
	protected String bearer(AppUser user) {
		return "Bearer " + jwt.issue(user).value();
	}
}
