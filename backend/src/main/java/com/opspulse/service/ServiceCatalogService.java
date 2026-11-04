package com.opspulse.service;

import com.opspulse.common.error.ConflictException;
import com.opspulse.common.error.NotFoundException;
import com.opspulse.domain.entity.MonitoredService;
import com.opspulse.domain.entity.Team;
import com.opspulse.repository.MonitoredServiceRepository;
import com.opspulse.repository.TeamRepository;
import com.opspulse.web.dto.ServiceDtos.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** CRUD for monitored services. Named to avoid clashing with Spring's @Service. */
@Service
@RequiredArgsConstructor
public class ServiceCatalogService {

    private final MonitoredServiceRepository services;
    private final TeamRepository teams;
    private final EntityManager em;

    @Transactional(readOnly = true)
    public List<ServiceResponse> list(UUID teamId) {
        var rows = teamId == null ? services.findAll(Sort.by("name")) : services.findByTeamId(teamId);
        return rows.stream().map(ServiceResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ServiceResponse get(UUID id) {
        return ServiceResponse.from(find(id));
    }

    @Transactional
    public ServiceResponse create(CreateServiceRequest req) {
        if (services.findByServiceKey(req.serviceKey()).isPresent()) {
            throw new ConflictException("Service key '" + req.serviceKey() + "' is already taken");
        }
        MonitoredService s = new MonitoredService();
        s.setName(req.name().trim());
        s.setServiceKey(req.serviceKey());
        s.setDescription(req.description());
        s.setTeam(team(req.teamId()));
        services.saveAndFlush(s);
        em.refresh(s);
        return ServiceResponse.from(s);
    }

    @Transactional
    public ServiceResponse update(UUID id, UpdateServiceRequest req) {
        MonitoredService s = find(id);
        if (req.name() != null) s.setName(req.name().trim());
        if (req.description() != null) s.setDescription(req.description());
        if (req.teamId() != null) s.setTeam(team(req.teamId()));
        if (req.status() != null) s.setStatus(req.status());
        return ServiceResponse.from(s);
    }

    /**
     * Incidents and alerts reference services without ON DELETE CASCADE, on
     * purpose: deleting a service must never silently wipe incident history.
     * The FK makes the database refuse; we translate that into a clear 409.
     */
    @Transactional
    public void delete(UUID id) {
        MonitoredService s = find(id);
        try {
            services.delete(s);
            services.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Service has incident history and cannot be deleted; set it to MAINTENANCE instead");
        }
    }

    private MonitoredService find(UUID id) {
        return services.findById(id).orElseThrow(() -> new NotFoundException("Service", id));
    }

    private Team team(UUID id) {
        return teams.findById(id).orElseThrow(() -> new NotFoundException("Team", id));
    }
}
