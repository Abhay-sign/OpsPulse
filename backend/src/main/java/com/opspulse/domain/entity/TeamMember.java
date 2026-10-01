package com.opspulse.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Explicit join entity rather than @ManyToMany: membership carries its own
 * data (added_at today, on-call rotation order later), and @ManyToMany makes
 * that impossible to extend without a painful migration.
 */
@Entity
@Table(name = "team_members")
@Getter @Setter @NoArgsConstructor
public class TeamMember {

    @EmbeddedId
    private TeamMemberId id;

    @MapsId("teamId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id")
    private Team team;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private UserAccount user;

    @Column(name = "added_at", nullable = false, updatable = false, insertable = false)
    private Instant addedAt;

    public TeamMember(Team team, UserAccount user) {
        this.team = team;
        this.user = user;
        this.id = new TeamMemberId(team.getId(), user.getId());
    }

    @Embeddable
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class TeamMemberId implements Serializable {
        @Column(name = "team_id") private UUID teamId;
        @Column(name = "user_id") private UUID userId;

        @Override public boolean equals(Object o) {
            return o instanceof TeamMemberId k
                    && Objects.equals(teamId, k.teamId)
                    && Objects.equals(userId, k.userId);
        }
        @Override public int hashCode() { return Objects.hash(teamId, userId); }
    }
}
