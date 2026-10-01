package com.opspulse.repository;

import com.opspulse.domain.entity.Team;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TeamRepository extends JpaRepository<Team, UUID> {
    Optional<Team> findBySlug(String slug);
    boolean existsBySlug(String slug);
}
