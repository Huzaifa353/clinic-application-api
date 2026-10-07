package com.preclinic.backend.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.preclinic.backend.auth.AuthDtos.LoginResponse;
import com.preclinic.backend.auth.AuthDtos.UserDto;
import com.preclinic.backend.common.ApiException;
import com.preclinic.backend.security.CurrentUser;
import com.preclinic.backend.security.JwtService;
import com.preclinic.backend.security.LoginAttemptLimiter;
import com.preclinic.backend.security.JwtService.IssuedToken;
import com.preclinic.backend.user.AppUser;
import com.preclinic.backend.user.AppUserRepository;
import com.preclinic.backend.user.ClinicRepository;

@Service
public class AuthService {

	private final AppUserRepository users;
	private final ClinicRepository clinics;
	private final PasswordEncoder encoder;
	private final JwtService jwt;
	private final CurrentUser currentUser;
	private final LoginAttemptLimiter limiter;
	/** Compared against when the email is unknown, so a miss costs the same as a wrong password. */
	private final String dummyHash;

	public AuthService(AppUserRepository users, ClinicRepository clinics, PasswordEncoder encoder, JwtService jwt,
			CurrentUser currentUser, LoginAttemptLimiter limiter) {
		this.users = users;
		this.clinics = clinics;
		this.encoder = encoder;
		this.jwt = jwt;
		this.currentUser = currentUser;
		this.limiter = limiter;
		this.dummyHash = encoder.encode("not-a-real-password");
	}

	@Transactional(readOnly = true)
	public LoginResponse login(String email, String password, String clientAddress) {
		limiter.checkAllowed(clientAddress, email);
		AppUser user = users.findByEmailIgnoreCase(email.trim()).filter(AppUser::isActive).orElse(null);
		boolean matches = encoder.matches(password, user != null ? user.getPasswordHash() : dummyHash);
		if (user == null || !matches) {
			limiter.recordFailure(clientAddress, email);
			throw ApiException.unauthorized("INVALID_CREDENTIALS", "Incorrect email or password.");
		}
		limiter.recordSuccess(clientAddress, email);
		IssuedToken token = jwt.issue(user);
		return new LoginResponse(token.value(), token.expiresAt(), toDto(user));
	}

	@Transactional(readOnly = true)
	public UserDto me() {
		AppUser user = users.findByIdAndClinicId(currentUser.id(), currentUser.clinicId())
				.filter(AppUser::isActive)
				.orElseThrow(() -> ApiException.unauthorized("UNAUTHENTICATED", "This account is no longer active."));
		return toDto(user);
	}

	@Transactional
	public void changePassword(String currentPassword, String newPassword) {
		AppUser user = users.findByIdAndClinicId(currentUser.id(), currentUser.clinicId())
				.orElseThrow(() -> ApiException.unauthorized("UNAUTHENTICATED", "Authentication is required."));
		if (!encoder.matches(currentPassword, user.getPasswordHash())) {
			throw ApiException.unprocessable("INVALID_CURRENT_PASSWORD", "The current password is incorrect.");
		}
		user.setPasswordHash(encoder.encode(newPassword));
	}

	private UserDto toDto(AppUser user) {
		String clinicName = clinics.findById(user.getClinicId()).map(c -> c.getName()).orElse("");
		return new UserDto(user.getId(), user.getFullName(), user.getRole().dbValue(), clinicName, user.getEmail());
	}
}
