package com.opspulse.repository;

import com.opspulse.domain.entity.TeamMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TeamMemberRepository
        extends JpaRepository<TeamMember, TeamMember.TeamMemberId> {

    List<TeamMember> findByTeamId(UUID teamId);
    List<TeamMember> findByUserId(UUID userId);
    void deleteByTeamIdAndUserId(UUID teamId, UUID userId);
}
