package com.preclinic.backend.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Writes an RFC 7807 problem body straight to the servlet response. Used by the security filters
 * (401/403), which run before Spring MVC and so cannot use {@link GlobalExceptionHandler}.
 * Messages passed in are constants, never user input.
 */
public final class ProblemJson {

	private ProblemJson() {
	}

	public static void write(HttpServletResponse response, HttpStatus status, String code, String detail)
			throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		String body = "{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase() + "\",\"status\":"
				+ status.value() + ",\"detail\":\"" + detail + "\",\"code\":\"" + code + "\"}";
		response.getWriter().write(body);
	}
}
