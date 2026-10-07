package com.preclinic.backend.common;

import java.time.LocalDate;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hands out gap-free-per-transaction human-readable numbers (patient no., token, invoice no. ...).
 * One atomic upsert increments the counter, so concurrent callers can never receive the same number.
 * It must run inside the caller's transaction: if that transaction rolls back, the increment rolls
 * back with it and no number is wasted.
 */
@Service
public class NumberSequenceService {

	private static final String NEXT = """
			insert into number_sequence (clinic_id, kind, scope, last_value)
			values (:clinicId, :kind, :scope, 1)
			on conflict (clinic_id, kind, scope)
			do update set last_value = number_sequence.last_value + 1
			returning last_value
			""";

	private final JdbcClient jdbc;

	public NumberSequenceService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Next lifetime number for the clinic (patients, invoices, receipts ...). */
	@Transactional(propagation = Propagation.MANDATORY)
	public long next(long clinicId, SequenceKind kind) {
		return next(clinicId, kind, "");
	}

	/** Next number within a scope, e.g. the queue token for one clinic-local day. */
	@Transactional(propagation = Propagation.MANDATORY)
	public long next(long clinicId, SequenceKind kind, String scope) {
		return jdbc.sql(NEXT)
				.param("clinicId", clinicId)
				.param("kind", kind.dbValue())
				.param("scope", scope)
				.query(Long.class)
				.single();
	}

	/** Next queue token for the given clinic-local day. */
	@Transactional(propagation = Propagation.MANDATORY)
	public long nextToken(long clinicId, LocalDate queueDate) {
		return next(clinicId, SequenceKind.TOKEN, queueDate.toString());
	}
}
