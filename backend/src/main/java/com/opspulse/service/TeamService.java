package com.opspulse.service;

import com.opspulse.common.error.ConflictException;
import com.opspulse.common.error.NotFoundException;
import com.opspulse.domain.entity.Team;
import com.opspulse.domain.entity.TeamMember;
import com.opspulse.domain.entity.UserAccount;
import com.opspulse.repository.MonitoredServiceRepository;
import com.opspulse.repository.TeamMemberRepository;
import com.opspulse.repository.TeamRepository;
import com.opspulse.repository.UserAccountRepository;
import com.opspulse.web.dto.TeamDtos.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TeamService {

    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final UserAccountRepository users;
    private final MonitoredServiceRepository services;
    private final EntityManager em;

    @Transactional(readOnly = true)
    public List<TeamResponse> list() {
        return teams.findAll(Sort.by("name")).stream().map(TeamResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public TeamResponse get(UUID id) {
        return TeamResponse.from(find(id));
    }

    @Transactional
    public TeamResponse create(CreateTeamRequest req) {
        String slug = req.slug() != null ? req.slug() : slugify(req.name());
        if (slug.isEmpty()) throw new IllegalArgumentException("Cannot derive a slug from that name; pass one");
        if (teams.existsBySlug(slug)) throw new ConflictException("A team with slug '" + slug + "' already exists");
        Team t = new Team();
        t.setName(req.name().trim());
        t.setSlug(slug);
        t.setDescription(req.description());
        teams.saveAndFlush(t);
        em.refresh(t);
        return TeamResponse.from(t);
    }

    @Transactional
    public TeamResponse update(UUID id, UpdateTeamRequest req) {
        Team t = find(id);
        if (req.name() != null) t.setName(req.name().trim());
        if (req.description() != null) t.setDescription(req.description());
        return TeamResponse.from(t);
    }

    /** Refused while services still belong to the team: they'd be orphaned with no owner to page. */
    @Transactional
    public void delete(UUID id) {
        Team t = find(id);
        if (!services.findByTeamId(id).isEmpty()) {
            throw new ConflictException("Team still owns services; reassign or delete them first");
        }
        teams.delete(t);
    }

    @Transactional(readOnly = true)
    public List<TeamMemberResponse> listMembers(UUID teamId) {
        find(teamId);
        return members.findByTeamId(teamId).stream().map(TeamMemberResponse::from).toList();
    }

    /** PUT semantics: adding someone who is already a member is a no-op, not an error. */
    @Transactional
    public TeamMemberResponse addMember(UUID teamId, UUID userId) {
        Team team = find(teamId);
        UserAccount user = users.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
        var existing = members.findById(new TeamMember.TeamMemberId(teamId, userId));
        if (existing.isPresent()) return TeamMemberResponse.from(existing.get());
        TeamMember m = members.saveAndFlush(new TeamMember(team, user));
        em.refresh(m);
        return TeamMemberResponse.from(m);
    }

    @Transactional
    public void removeMember(UUID teamId, UUID userId) {
        members.deleteByTeamIdAndUserId(teamId, userId);
    }

    private Team find(UUID id) {
        return teams.findById(id).orElseThrow(() -> new NotFoundException("Team", id));
    }

    static String slugify(String name) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return ascii.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
