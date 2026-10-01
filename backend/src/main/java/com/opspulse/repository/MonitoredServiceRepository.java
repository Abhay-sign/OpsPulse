package com.opspulse.repository;

import com.opspulse.domain.entity.MonitoredService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MonitoredServiceRepository extends JpaRepository<MonitoredService, UUID> {

    Optional<MonitoredService> findByServiceKey(String serviceKey);

    List<MonitoredService> findByTeamId(UUID teamId);

    /**
     * Ingest-path read. Fetches the escalation ladder in one query -- without
     * this join fetch, creating an incident triggers N+1 lazy loads per alert.
     */
    @Query("""
           select s from MonitoredService s
             left join fetch s.escalationPolicy p
             left join fetch p.rules
            where s.id = :id
           """)
    Optional<MonitoredService> findByIdWithPolicy(UUID id);
}
