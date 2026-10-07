package com.preclinic.backend.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import com.preclinic.backend.common.ApiException;
import com.preclinic.backend.user.Role;

/**
 * The authenticated caller, read from the validated JWT. The clinic always comes from here, never
 * from request parameters, so one clinic can never address another's data.
 */
@Component
public class CurrentUser {

	static final String CLAIM_CLINIC_ID = "clinicId";
	static final String CLAIM_ROLE = "role";
	static final String CLAIM_NAME = "name";
	static final String CLAIM_EMAIL = "email";

	public long id() {
		return Long.parseLong(jwt().getSubject());
	}

	public long clinicId() {
		Long clinicId = jwt().getClaim(CLAIM_CLINIC_ID);
		if (clinicId == null) {
			throw unauthenticated();
		}
		return clinicId;
	}

	public Role role() {
		return Role.fromDb(jwt().getClaimAsString(CLAIM_ROLE));
	}

	public boolean isDoctor() {
		return role() == Role.DOCTOR;
	}

	public String name() {
		return jwt().getClaimAsString(CLAIM_NAME);
	}

	private Jwt jwt() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
			return jwt;
		}
		throw unauthenticated();
	}

	private static ApiException unauthenticated() {
		return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required.");
	}
}
