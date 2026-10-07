package com.preclinic.backend.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Refuses to start with the "prod" profile while any development default is still in place: the
 * built-in signing/encryption secrets are public, and the demo users (password 123456) must not exist.
 * Fail loudly at startup rather than run an unsafe system.
 */
@Component
@Profile("prod")
public class ProductionSafetyCheck {

	static final String DEV_JWT_SECRET = "dev-only-secret-change-me-0123456789abcdef";
	static final String DEV_SECRETS_KEY = "dev-only-secrets-key-change-me-in-production";

	private final String jwtSecret;
	private final String secretsKey;
	private final boolean devSeed;

	public ProductionSafetyCheck(@Value("${clinstra.jwt.secret}") String jwtSecret,
			@Value("${clinstra.secrets.key}") String secretsKey,
			@Value("${clinstra.dev-seed.enabled:false}") boolean devSeed) {
		this.jwtSecret = jwtSecret;
		this.secretsKey = secretsKey;
		this.devSeed = devSeed;
	}

	@PostConstruct
	public void verify() {
		if (jwtSecret.equals(DEV_JWT_SECRET) || jwtSecret.length() < 48) {
			throw new IllegalStateException(
					"JWT_SECRET must be set to a private random value of at least 48 characters in production.");
		}
		if (secretsKey.equals(DEV_SECRETS_KEY) || secretsKey.length() < 32) {
			throw new IllegalStateException(
					"SECRETS_KEY must be set to a private random value of at least 32 characters in production.");
		}
		if (devSeed) {
			throw new IllegalStateException("clinstra.dev-seed.enabled must be false in production (it creates demo users).");
		}
	}
}
