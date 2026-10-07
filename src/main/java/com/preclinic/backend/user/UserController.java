package com.preclinic.backend.user;

import java.util.List;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.preclinic.backend.common.Api;
import com.preclinic.backend.security.CurrentUser;
import com.preclinic.backend.user.UserDtos.UserSummary;

@RestController
@RequestMapping(Api.V1 + "/users")
public class UserController {

	private final AppUserRepository users;
	private final CurrentUser currentUser;

	public UserController(AppUserRepository users, CurrentUser currentUser) {
		this.users = users;
		this.currentUser = currentUser;
	}

	/** Active users of the caller's clinic, optionally filtered by role ("doctor" or "assistant"). */
	@GetMapping
	@Transactional(readOnly = true)
	public List<UserSummary> list(@RequestParam(required = false) String role) {
		Role filter = role == null || role.isBlank() ? null : Role.fromDb(role.toLowerCase());
		return users.findByClinicIdAndActiveTrueOrderByFullNameAsc(currentUser.clinicId()).stream()
				.filter(u -> filter == null || u.getRole() == filter)
				.map(u -> new UserSummary(u.getId(), u.getFullName(), u.getRole().dbValue()))
				.toList();
	}
}
