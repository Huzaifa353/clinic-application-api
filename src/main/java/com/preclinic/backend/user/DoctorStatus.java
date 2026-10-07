package com.preclinic.backend.user;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The doctor's manual "away" flag. Whether the doctor is consulting is derived from the queue,
 * not stored here.
 */
@Entity
@Table(name = "doctor_status")
@Getter
@Setter
@NoArgsConstructor
public class DoctorStatus {

	@Id
	@Column(name = "user_id")
	private Long userId;

	@Column(nullable = false)
	private boolean away;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public DoctorStatus(Long userId, Instant updatedAt) {
		this.userId = userId;
		this.updatedAt = updatedAt;
	}
}
