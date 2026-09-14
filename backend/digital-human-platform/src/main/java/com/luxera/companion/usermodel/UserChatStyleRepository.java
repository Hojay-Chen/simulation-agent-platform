package com.luxera.companion.usermodel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserChatStyleRepository extends JpaRepository<UserChatStyle, String> {
    Optional<UserChatStyle> findByCompanionId(String companionId);
}
