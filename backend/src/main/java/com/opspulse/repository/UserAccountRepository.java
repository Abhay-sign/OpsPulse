package com.opspulse.repository;

import com.opspulse.domain.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    /** Active members of a team -- the fan-out target for a TEAM escalation rule. */
    @Query("""
           select u from UserAccount u
             join TeamMember tm on tm.user = u
            where tm.team.id = :teamId
              and u.active = true
           """)
    List<UserAccount> findActiveMembersOfTeam(UUID teamId);
}
