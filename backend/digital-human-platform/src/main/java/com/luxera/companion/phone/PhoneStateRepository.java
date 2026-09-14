package com.luxera.companion.phone;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PhoneStateRepository extends JpaRepository<PhoneState, String> {
    Optional<PhoneState> findByCompanionId(String companionId);
}
