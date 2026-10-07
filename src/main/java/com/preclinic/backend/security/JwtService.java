package com.preclinic.backend.security;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.preclinic.backend.common.ClinicClock;
import com.preclinic.backend.user.AppUser;

@Service
public class JwtService {

	public record IssuedToken(String value, Instant expiresAt) {
	}

	private final JwtEncoder encoder;
	private final ClinicClock clock;
	private final Duration ttl;

	public JwtService(JwtEncoder encoder, ClinicClock clock, @Value("${clinstra.jwt.ttl-hours:12}") long ttlHours) {
		this.encoder = encoder;
		this.clock = clock;
		this.ttl = Duration.ofHours(ttlHours);
	}

	public IssuedToken issue(AppUser user) {
		Instant now = clock.now();
		Instant expiresAt = now.plus(ttl);
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.subject(String.valueOf(user.getId()))
				.issuedAt(now)
				.expiresAt(expiresAt)
				.claim(CurrentUser.CLAIM_CLINIC_ID, user.getClinicId())
				.claim(CurrentUser.CLAIM_ROLE, user.getRole().dbValue())
				.claim(CurrentUser.CLAIM_NAME, user.getFullName())
				.claim(CurrentUser.CLAIM_EMAIL, user.getEmail())
				.build();
		String token = encoder
				.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
		return new IssuedToken(token, expiresAt);
	}
}
