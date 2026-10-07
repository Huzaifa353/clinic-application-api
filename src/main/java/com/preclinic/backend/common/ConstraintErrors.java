package com.preclinic.backend.common;

import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;

/**
 * Translates PostgreSQL constraint violations into meaningful API errors. The database enforces the
 * hard rules (see the Flyway migrations); this table gives each one a stable code and a readable
 * message. A violated constraint that is not listed here becomes a generic {@code DATA_CONFLICT}.
 */
final class ConstraintErrors {

	record Mapped(HttpStatus status, String code, String message) {
	}

	private static final Map<String, Mapped> BY_CONSTRAINT = Map.of(
			"ux_app_user_email", new Mapped(HttpStatus.CONFLICT, "EMAIL_TAKEN",
					"A user with this email already exists."),
			"ux_visit_one_consulting", new Mapped(HttpStatus.CONFLICT, "QUEUE_DOCTOR_BUSY",
					"The doctor is already consulting another patient."),
			"visit_clinic_id_queue_date_token_no_key", new Mapped(HttpStatus.CONFLICT, "TOKEN_TAKEN",
					"That token number is already used for this day."),
			"ux_appt_doctor_slot", new Mapped(HttpStatus.CONFLICT, "APPOINTMENT_SLOT_TAKEN",
					"The doctor already has an appointment at that date and time."));

	private ConstraintErrors() {
	}

	/** Looks for a known constraint name anywhere in the exception's cause chain messages. */
	static Optional<Mapped> find(Throwable error) {
		for (Throwable t = error; t != null; t = t.getCause()) {
			String message = t.getMessage();
			if (message == null) {
				continue;
			}
			for (Map.Entry<String, Mapped> entry : BY_CONSTRAINT.entrySet()) {
				if (message.contains(entry.getKey())) {
					return Optional.of(entry.getValue());
				}
			}
		}
		return Optional.empty();
	}
}
