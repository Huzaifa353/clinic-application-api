package com.preclinic.backend.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.preclinic.backend.common.ClinicClock;

/**
 * Local-development convenience: when the user table is empty, creates the demo doctor and assistant
 * the frontend already knows (the SQL migrations deliberately create no users, because password
 * hashes must come from the application). Off unless {@code clinstra.dev-seed.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "clinstra.dev-seed.enabled", havingValue = "true")
public class DevUserSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(DevUserSeeder.class);

	private final AppUserRepository users;
	private final ClinicRepository clinics;
	private final DoctorProfileRepository profiles;
	private final DoctorStatusRepository statuses;
	private final PasswordEncoder encoder;
	private final ClinicClock clock;
	private final String password;

	public DevUserSeeder(AppUserRepository users, ClinicRepository clinics, DoctorProfileRepository profiles,
			DoctorStatusRepository statuses, PasswordEncoder encoder, ClinicClock clock,
			@Value("${clinstra.dev-seed.password}") String password) {
		this.users = users;
		this.clinics = clinics;
		this.profiles = profiles;
		this.statuses = statuses;
		this.encoder = encoder;
		this.clock = clock;
		this.password = password;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (users.count() > 0) {
			return;
		}
		Clinic clinic = clinics.findFirstByOrderByIdAsc().orElse(null);
		if (clinic == null) {
			log.warn("Dev seed skipped: no clinic row exists (has V12 run?)");
			return;
		}

		AppUser doctor = newUser(clinic, "admin@clinstra.com", "Dr. Sara Ahmed", Role.DOCTOR);
		DoctorProfile profile = new DoctorProfile(doctor.getId());
		profile.setQualifications("MBBS, FCPS");
		profile.setSpecialization("General Practice");
		profile.setRegistrationNo("PMC-45217-P");
		profile.setAddressLine1("House 12, Street 4, F-10/2");
		profile.setCity("Islamabad");
		profile.setState("Islamabad Capital Territory");
		profile.setZip("44000");
		profile.setCountry("Pakistan");
		profiles.save(profile);
		statuses.save(new DoctorStatus(doctor.getId(), clock.now()));

		newUser(clinic, "sana.tariq@clinstra.com", "Sana Tariq", Role.ASSISTANT);
		log.warn("Dev seed: created doctor admin@clinstra.com and assistant sana.tariq@clinstra.com "
				+ "(password from clinstra.dev-seed.password). Never enable this in production.");
	}

	private AppUser newUser(Clinic clinic, String email, String name, Role role) {
		AppUser user = new AppUser();
		user.setClinicId(clinic.getId());
		user.setEmail(email);
		user.setFullName(name);
		user.setRole(role);
		user.setPasswordHash(encoder.encode(password));
		return users.save(user);
	}
}
