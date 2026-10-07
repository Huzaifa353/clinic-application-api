package com.preclinic.backend.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.preclinic.backend.auth.AuthDtos.ChangePasswordRequest;
import com.preclinic.backend.auth.AuthDtos.LoginRequest;
import com.preclinic.backend.auth.AuthDtos.LoginResponse;
import com.preclinic.backend.auth.AuthDtos.UserDto;
import com.preclinic.backend.common.Api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping(Api.V1 + "/auth")
public class AuthController {

	private final AuthService auth;

	public AuthController(AuthService auth) {
		this.auth = auth;
	}

	@PostMapping("/login")
	public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
		return auth.login(request.email(), request.password(), http.getRemoteAddr());
	}

	@GetMapping("/me")
	public UserDto me() {
		return auth.me();
	}

	@PostMapping("/change-password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
		auth.changePassword(request.currentPassword(), request.newPassword());
	}
}
