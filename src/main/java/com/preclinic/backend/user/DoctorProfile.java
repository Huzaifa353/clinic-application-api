package com.preclinic.backend.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Professional identity printed on documents; one row per doctor user. */
@Entity
@Table(name = "doctor_profile")
@Getter
@Setter
@NoArgsConstructor
public class DoctorProfile {

	@Id
	@Column(name = "user_id")
	private Long userId;

	private String qualifications;

	private String specialization;

	@Column(name = "registration_no")
	private String registrationNo;

	@Column(name = "address_line1")
	private String addressLine1;

	@Column(name = "address_line2")
	private String addressLine2;

	private String city;

	private String state;

	private String zip;

	private String country;

	@Column(name = "preferred_print_language")
	private String preferredPrintLanguage;

	public DoctorProfile(Long userId) {
		this.userId = userId;
	}
}
