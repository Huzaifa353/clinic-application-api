package com.preclinic.backend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.preclinic.backend.common.ClinicClock;
import com.preclinic.backend.security.JwtService;
import com.preclinic.backend.user.AppUser;
import com.preclinic.backend.user.AppUserRepository;
import com.preclinic.backend.user.ClinicRepository;
import com.preclinic.backend.user.Role;

/**
 * Base for tests that need REAL commits (atomic rollback, concurrency, live events): they are not
 * wrapped in a rolled-back transaction, so they create a small committed fixture and delete it again
 * afterwards. Everything else in the suite uses {@link ApiIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class CommittedDataTest {

	@Autowired
	protected MockMvc mvc;
	@Autowired
	protected JdbcClient jdbc;
	@Autowired
	protected AppUserRepository users;
	@Autowired
	protected ClinicRepository clinics;
	@Autowired
	protected PasswordEncoder encoder;
	@Autowired
	protected JwtService jwt;
	@Autowired
	protected ClinicClock clock;
	@Autowired
	protected PlatformTransactionManager transactionManager;

	protected long clinicId;
	protected AppUser doctor;
	protected AppUser assistant;
	protected String asDoctor;
	protected String asAssistant;

	protected TransactionTemplate tx() {
		return new TransactionTemplate(transactionManager);
	}

	@BeforeEach
	void commitBaseFixture() {
		clinicId = clinics.findFirstByOrderByIdAsc().orElseThrow().getId();
		cleanAll();
		tx().executeWithoutResult(status -> {
			doctor = commitUser("doctor@committed.test", "Dr. Committed", Role.DOCTOR);
			assistant = commitUser("assistant@committed.test", "Committed Assistant", Role.ASSISTANT);
		});
		asDoctor = "Bearer " + jwt.issue(doctor).value();
		asAssistant = "Bearer " + jwt.issue(assistant).value();
	}

	@AfterEach
	void removeCommittedData() {
		cleanAll();
	}

	private AppUser commitUser(String email, String name, Role role) {
		AppUser user = new AppUser();
		user.setClinicId(clinicId);
		user.setEmail(email);
		user.setFullName(name);
		user.setRole(role);
		user.setPasswordHash(encoder.encode("x"));
		return users.save(user);
	}

	protected long commitPatient(String name, String mobile) {
		return tx().execute(status -> jdbc.sql("""
				insert into patient (clinic_id, patient_no, name, mobile, mobile_norm)
				values (:c, (select coalesce(max(patient_no), 0) + 1 from patient where clinic_id = :c), :name, :mobile, :mobile)
				returning id
				""").param("c", clinicId).param("name", name).param("mobile", mobile).query(Long.class).single());
	}

	/**
	 * Deletes everything the committed tests (and only they) can have created. Payments and invoices are
	 * emptied with TRUNCATE because row triggers deliberately forbid deleting them.
	 */
	protected void cleanAll() {
		jdbc.sql("truncate payment, invoice").update();
		jdbc.sql("delete from notification").update();
		jdbc.sql("delete from audit_log").update();
		jdbc.sql("delete from follow_up").update();
		jdbc.sql("delete from consultation").update();
		jdbc.sql("delete from visit").update();
		jdbc.sql("delete from appointment").update();
		jdbc.sql("delete from patient").update();
		jdbc.sql("delete from doctor_status where user_id in (select id from app_user where email like '%@committed.test')").update();
		jdbc.sql("delete from doctor_profile where user_id in (select id from app_user where email like '%@committed.test')").update();
		jdbc.sql("delete from app_user where email like '%@committed.test'").update();
		jdbc.sql("delete from number_sequence where clinic_id = :c").param("c", clinicId).update();
	}
}
