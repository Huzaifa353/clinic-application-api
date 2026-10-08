package com.preclinic.backend.user;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.preclinic.backend.common.ClinicClock;

/**
 * First start of an installed copy: creates the clinic's own doctor and assistant accounts from the
 * setup window's answers (a small properties file the launcher writes). The file holds passwords, so it is
 * deleted as soon as it has been read. Does nothing once any user exists.
 */
@Component
@Order(10)
@ConditionalOnExpression("!'${clinstra.first-run.file:}'.isEmpty()")
public class FirstRunSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(FirstRunSeeder.class);

	private final AppUserRepository users;
	private final ClinicRepository clinics;
	private final DoctorProfileRepository profiles;
	private final DoctorStatusRepository statuses;
	private final PasswordEncoder encoder;
	private final ClinicClock clock;
	private final JdbcClient jdbc;
	private final Path file;

	public FirstRunSeeder(AppUserRepository users, ClinicRepository clinics, DoctorProfileRepository profiles,
			DoctorStatusRepository statuses, PasswordEncoder encoder, ClinicClock clock, JdbcClient jdbc,
			@Value("${clinstra.first-run.file}") String file) {
		this.users = users;
		this.clinics = clinics;
		this.profiles = profiles;
		this.statuses = statuses;
		this.encoder = encoder;
		this.clock = clock;
		this.jdbc = jdbc;
		this.file = Path.of(file);
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) throws IOException {
		if (!Files.exists(file)) {
			return;
		}
		try {
			if (users.count() > 0) {
				return;
			}
			Properties p = new Properties();
			try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				p.load(in);
			}
			Clinic clinic = clinics.findFirstByOrderByIdAsc()
					.orElseThrow(() -> new IllegalStateException("First-run setup: no clinic row exists (has V12 run?)"));

			String clinicName = required(p, "clinicName");
			clinic.setName(clinicName);
			clinics.save(clinic);
			jdbc.sql("update clinic_settings set print_clinic_name = :n where clinic_id = :c")
					.param("n", clinicName).param("c", clinic.getId()).update();

			AppUser doctor = newUser(clinic, p, "doctor", Role.DOCTOR);
			DoctorProfile profile = new DoctorProfile(doctor.getId());
			profiles.save(profile);
			statuses.save(new DoctorStatus(doctor.getId(), clock.now()));
			newUser(clinic, p, "assistant", Role.ASSISTANT);
			log.info("First-run setup: created the clinic '{}', its doctor and its assistant.", clinicName);
		}
		finally {
			Files.deleteIfExists(file);
		}
	}

	private AppUser newUser(Clinic clinic, Properties p, String prefix, Role role) {
		String password = required(p, prefix + ".password");
		if (password.length() < 8 || password.chars().noneMatch(Character::isLetter)
				|| password.chars().noneMatch(Character::isDigit)) {
			throw new IllegalStateException("First-run setup: the " + prefix + " password needs 8+ characters with a letter and a number.");
		}
		AppUser user = new AppUser();
		user.setClinicId(clinic.getId());
		user.setEmail(required(p, prefix + ".email").toLowerCase());
		user.setFullName(required(p, prefix + ".name"));
		user.setRole(role);
		user.setPasswordHash(encoder.encode(password));
		return users.save(user);
	}

	private static String required(Properties p, String key) {
		String value = p.getProperty(key);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("First-run setup: '" + key + "' is missing.");
		}
		return value.trim();
	}
}
