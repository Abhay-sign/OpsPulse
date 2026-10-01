package com.opspulse.repository;

import com.opspulse.domain.entity.EscalationPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EscalationPolicyRepository extends JpaRepository<EscalationPolicy, UUID> {

    List<EscalationPolicy> findByTeamId(UUID teamId);

    @Query("""
           select p from EscalationPolicy p
             left join fetch p.rules
            where p.id = :id
           """)
    Optional<EscalationPolicy> findByIdWithRules(UUID id);
}
