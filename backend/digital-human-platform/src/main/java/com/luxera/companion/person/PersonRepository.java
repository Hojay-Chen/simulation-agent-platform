package com.luxera.companion.person;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PersonRepository extends JpaRepository<Person, String> {
    Optional<Person> findByUserId(String userId);
    Optional<Person> findByCompanionId(String companionId);
    List<Person> findByPersonType(String personType);
}
