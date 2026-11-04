package com.opspulse.web.dto;

import com.opspulse.domain.entity.Team;
import com.opspulse.domain.entity.TeamMember;
import com.opspulse.domain.enums.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class TeamDtos {
    private TeamDtos() {}

    public static final String SLUG_REGEX = "^[a-z0-9]+(?:-[a-z0-9]+)*$";

    public record TeamResponse(UUID id, String name, String slug, String description, Instant createdAt) {
        public static TeamResponse from(Team t) {
            return new TeamResponse(t.getId(), t.getName(), t.getSlug(), t.getDescription(), t.getCreatedAt());
        }
    }

    /** slug is optional; derived from name when omitted ("Payments Core" -> "payments-core"). */
    public record CreateTeamRequest(@NotBlank @Size(max = 128) String name,
                                    @Size(max = 128) @Pattern(regexp = SLUG_REGEX) String slug,
                                    String description) {}

    public record UpdateTeamRequest(@Size(min = 1, max = 128) String name, String description) {}

    public record TeamMemberResponse(UUID userId, String email, String fullName, Role role, Instant addedAt) {
        public static TeamMemberResponse from(TeamMember m) {
            var u = m.getUser();
            return new TeamMemberResponse(u.getId(), u.getEmail(), u.getFullName(), u.getRole(), m.getAddedAt());
        }
    }
}
