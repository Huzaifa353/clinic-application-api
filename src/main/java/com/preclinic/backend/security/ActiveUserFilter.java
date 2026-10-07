package com.preclinic.backend.security;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import com.preclinic.backend.common.ProblemJson;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * A JWT is valid until it expires, so on its own it would keep working after the account was
 * deactivated or its role changed. This runs right after the token is validated and rechecks the
 * user in the database (one primary-key lookup), so a deactivation takes effect on the very next call.
 *
 * Deliberately not a Spring bean: a Filter bean would also be registered with the servlet container and
 * run twice. {@link SecurityConfig} adds it to the security chain only.
 */
class ActiveUserFilter extends OncePerRequestFilter {

	private final JdbcClient jdbc;

	ActiveUserFilter(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth instanceof JwtAuthenticationToken token && !stillValid(token)) {
			SecurityContextHolder.clearContext();
			ProblemJson.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
					"This account is no longer active or its permissions changed. Please sign in again.");
			return;
		}
		chain.doFilter(request, response);
	}

	private boolean stillValid(JwtAuthenticationToken token) {
		try {
			long userId = Long.parseLong(token.getToken().getSubject());
			Long clinicId = token.getToken().getClaim(CurrentUser.CLAIM_CLINIC_ID);
			String role = token.getToken().getClaimAsString(CurrentUser.CLAIM_ROLE);
			return clinicId != null && role != null && jdbc.sql(
					"select 1 from app_user where id = :id and clinic_id = :c and role = :r and active")
					.param("id", userId).param("c", clinicId).param("r", role).query(Integer.class).optional().isPresent();
		}
		catch (NumberFormatException e) {
			return false;
		}
	}
}
