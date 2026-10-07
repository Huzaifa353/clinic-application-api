package com.preclinic.backend.user;

import java.util.Arrays;

/** The two roles of the product. Stored in the database as lower-case text. */
public enum Role {

	DOCTOR("doctor"),
	ASSISTANT("assistant");

	private final String dbValue;

	Role(String dbValue) {
		this.dbValue = dbValue;
	}

	/** Lower-case value used in the database, the JWT and the API (matches the frontend's UserRole). */
	public String dbValue() {
		return dbValue;
	}

	public static Role fromDb(String value) {
		return Arrays.stream(values())
				.filter(r -> r.dbValue.equals(value))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Unknown role: " + value));
	}
}
