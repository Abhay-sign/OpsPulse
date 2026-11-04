package com.opspulse.web;

import com.opspulse.service.TeamService;
import com.opspulse.web.dto.TeamDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/teams")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teams;

    @GetMapping
    public List<TeamResponse> list() { return teams.list(); }

    @GetMapping("/{id}")
    public TeamResponse get(@PathVariable UUID id) { return teams.get(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public TeamResponse create(@Valid @RequestBody CreateTeamRequest req) { return teams.create(req); }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public TeamResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateTeamRequest req) {
        return teams.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID id) { teams.delete(id); }

    @GetMapping("/{id}/members")
    public List<TeamMemberResponse> members(@PathVariable UUID id) { return teams.listMembers(id); }

    /** PUT, not POST: membership is a set, so adding is idempotent. */
    @PutMapping("/{id}/members/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public TeamMemberResponse addMember(@PathVariable UUID id, @PathVariable UUID userId) {
        return teams.addMember(id, userId);
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void removeMember(@PathVariable UUID id, @PathVariable UUID userId) { teams.removeMember(id, userId); }
}
