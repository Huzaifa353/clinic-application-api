package com.preclinic.backend.common;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.preclinic.backend.security.CurrentUser;

/**
 * Append-only trail of who did what to which record (voided invoices, received payments, visit status
 * changes ...). Written inside the caller's transaction, so an action that rolls back leaves no entry.
 */
@Service
public class AuditService {

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;

	public AuditService(JdbcClient jdbc, CurrentUser currentUser) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
	}

	/** {@code detail} is a small flat map (strings, numbers, booleans, null) stored as JSON. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void log(String action, String entity, Long entityId, Map<String, ?> detail) {
		jdbc.sql("""
				insert into audit_log (clinic_id, user_id, action, entity, entity_id, detail)
				values (:c, :u, :action, :entity, :entityId, cast(:detail as jsonb))
				""")
				.param("c", currentUser.clinicId()).param("u", currentUser.id()).param("action", action)
				.param("entity", entity).param("entityId", entityId).param("detail", toJson(detail))
				.update();
	}

	public static Map<String, Object> detail(Object... keyValues) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i + 1 < keyValues.length; i += 2) {
			map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
		}
		return map;
	}

	static String toJson(Map<String, ?> detail) {
		if (detail == null || detail.isEmpty()) {
			return "{}";
		}
		StringBuilder sb = new StringBuilder("{");
		boolean first = true;
		for (Map.Entry<String, ?> e : detail.entrySet()) {
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append(quote(e.getKey())).append(':');
			Object v = e.getValue();
			if (v == null) {
				sb.append("null");
			}
			else if (v instanceof Number || v instanceof Boolean) {
				sb.append(v);
			}
			else {
				sb.append(quote(v.toString()));
			}
		}
		return sb.append('}').toString();
	}

	private static String quote(String s) {
		StringBuilder sb = new StringBuilder("\"");
		for (char c : s.toCharArray()) {
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					}
					else {
						sb.append(c);
					}
				}
			}
		}
		return sb.append('"').toString();
	}
}
