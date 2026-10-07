package com.preclinic.backend.common;

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Helpers for PostgreSQL {@code text[]} columns. Writing goes through a quoted array literal that the
 * SQL casts ({@code cast(:x as text[])}), which avoids driver-specific array binding; reading unwraps
 * the JDBC array.
 */
public final class PgArrays {

	private PgArrays() {
	}

	/** {@code {"a","b"}} with backslashes and quotes escaped; blank entries are dropped. */
	public static String literal(List<String> values) {
		if (values == null) {
			return "{}";
		}
		StringBuilder sb = new StringBuilder("{");
		boolean first = true;
		for (String value : values) {
			if (value == null || value.isBlank()) {
				continue;
			}
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append('"').append(value.trim().replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
		}
		return sb.append('}').toString();
	}

	public static List<String> read(Array array) throws SQLException {
		if (array == null) {
			return List.of();
		}
		Object[] items = (Object[]) array.getArray();
		List<String> result = new ArrayList<>(items.length);
		for (Object item : items) {
			result.add((String) item);
		}
		return result;
	}
}
