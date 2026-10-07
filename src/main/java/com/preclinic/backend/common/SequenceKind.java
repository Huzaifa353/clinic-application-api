package com.preclinic.backend.common;

/** The counters kept in the {@code number_sequence} table (see V2). */
public enum SequenceKind {

	PATIENT("patient"),
	APPOINTMENT("appointment"),
	/** Daily queue token; scope is the clinic-local date. */
	TOKEN("token"),
	CONSULTATION("consultation"),
	INVOICE("invoice"),
	RECEIPT("receipt");

	private final String dbValue;

	SequenceKind(String dbValue) {
		this.dbValue = dbValue;
	}

	public String dbValue() {
		return dbValue;
	}
}
