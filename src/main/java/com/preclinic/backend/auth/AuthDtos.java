package com.preclinic.backend.auth;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

	private AuthDtos() {
	}

	public record LoginRequest(@NotBlank String email, @NotBlank String password) {
	}

	/** Mirrors the frontend's {@code ClinstraUser}: role is "doctor" or "assistant". */
	public record UserDto(long id, String name, String role, String clinic, String email) {
	}

	public record LoginResponse(String token, Instant expiresAt, UserDto user) {
	}

	/** New passwords need 8-100 characters with at least one letter and one number. */
	public record ChangePasswordRequest(@NotBlank String currentPassword,
			@NotBlank @Size(max = 100)
			@Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$",
					message = "must be at least 8 characters with a letter and a number") String newPassword) {
	}
}
