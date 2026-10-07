package com.preclinic.backend.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ClinicRepository extends JpaRepository<Clinic, Long> {

	Optional<Clinic> findFirstByOrderByIdAsc();
}
