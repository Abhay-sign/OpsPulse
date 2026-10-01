package com.opspulse.repository;

import com.opspulse.domain.entity.IncidentEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface IncidentEventRepository extends JpaRepository<IncidentEvent, UUID> {

    /** Incident timeline, oldest first -- how the UI renders it. */
    @Query("""
           select e from IncidentEvent e
             left join fetch e.actor
            where e.incident.id = :incidentId
            order by e.createdAt asc
           """)
    List<IncidentEvent> findTimeline(UUID incidentId);

    /** Audit view: everything one user has done, newest first. */
    Page<IncidentEvent> findByActorIdOrderByCreatedAtDesc(UUID actorId, Pageable pageable);
}
