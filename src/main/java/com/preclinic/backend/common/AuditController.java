package com.preclinic.backend.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.preclinic.backend.security.CurrentUser;

/**
 * Who did what, newest first: voided invoices, received payments, queue changes, consultation saves,
 * settings changes. Read-only and doctor-only (the doctor is the clinic's administrator).
 */
@RestController
@RequestMapping(Api.V1 + "/audit-log")
@PreAuthorize("hasRole('DOCTOR')")
public class AuditController {

	/** {@code detail} is the stored JSON, passed through as text. */
	public record AuditEntryDto(long id, Instant at, Long userId, String userName, String action, String entity,
			Long entityId, String detail) {
	}

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ClinicClock clock;

	public AuditController(JdbcClient jdbc, CurrentUser currentUser, ClinicClock clock) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.clock = clock;
	}

	@GetMapping
	@Transactional(readOnly = true)
	public PageResult<AuditEntryDto> list(
			@RequestParam(required = false) String entity,
			@RequestParam(required = false) Long entityId,
			@RequestParam(required = false) String action,
			@RequestParam(required = false) Long userId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(defaultValue = "0") int skip,
			@RequestParam(defaultValue = "50") int limit) {
		Map<String, Object> params = new HashMap<>();
		params.put("c", currentUser.clinicId());
		params.put("tz", clock.zone().getId());
		StringBuilder where = new StringBuilder(" where a.clinic_id = :c");
		if (entity != null && !entity.isBlank()) {
			where.append(" and a.entity = :entity");
			params.put("entity", entity);
		}
		if (entityId != null) {
			where.append(" and a.entity_id = :entityId");
			params.put("entityId", entityId);
		}
		if (action != null && !action.isBlank()) {
			// "invoice" matches invoice.void, invoice.create ...; a full name matches exactly
			where.append(" and (a.action = :action or a.action like :actionPrefix)");
			params.put("action", action);
			params.put("actionPrefix", action.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + ".%");
		}
		if (userId != null) {
			where.append(" and a.user_id = :userId");
			params.put("userId", userId);
		}
		if (from != null) {
			where.append(" and (a.at at time zone :tz)::date >= :from");
			params.put("from", from);
		}
		if (to != null) {
			where.append(" and (a.at at time zone :tz)::date <= :to");
			params.put("to", to);
		}
		long total = jdbc.sql("select count(*) from audit_log a" + where).params(params).query(Long.class).single();
		params.put("limit", Math.min(Math.max(limit, 1), 200));
		params.put("skip", Math.max(skip, 0));
		List<AuditEntryDto> page = jdbc.sql("""
				select a.id, a.at, a.user_id, u.full_name, a.action, a.entity, a.entity_id, a.detail::text as detail
				from audit_log a left join app_user u on u.id = a.user_id""" + where
				+ " order by a.at desc, a.id desc limit :limit offset :skip").params(params)
				.query((rs, i) -> {
					long user = rs.getLong("user_id");
					boolean hasUser = !rs.wasNull();
					long entityRef = rs.getLong("entity_id");
					boolean hasEntity = !rs.wasNull();
					return new AuditEntryDto(rs.getLong("id"), rs.getObject("at", OffsetDateTime.class).toInstant(),
							hasUser ? user : null, rs.getString("full_name"), rs.getString("action"),
							rs.getString("entity"), hasEntity ? entityRef : null, rs.getString("detail"));
				}).list();
		return new PageResult<>(page, total);
	}
}
