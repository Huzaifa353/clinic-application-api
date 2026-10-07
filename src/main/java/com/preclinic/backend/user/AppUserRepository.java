package com.preclinic.backend.user;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

	Optional<AppUser> findByEmailIgnoreCase(String email);

	Optional<AppUser> findByIdAndClinicId(Long id, Long clinicId);

	List<AppUser> findByClinicIdAndActiveTrueOrderByFullNameAsc(Long clinicId);

	List<AppUser> findByClinicIdAndRoleAndActiveTrueOrderByIdAsc(Long clinicId, Role role);
}
