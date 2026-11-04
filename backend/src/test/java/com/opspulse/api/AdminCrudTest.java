package com.opspulse.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminCrudTest extends ApiTestSupport {

    @Test
    void teamLifecycleWithMembers() throws Exception {
        String admin = adminToken();
        String name = "Payments Core " + unique("x");
        var team = body(send(post("/api/v1/teams"), admin, Map.of("name", name))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdAt").isNotEmpty()));
        String teamId = team.get("id").asText();
        assertThat(team.get("slug").asText()).startsWith("payments-core-x-");

        String userId = createUser(unique("m") + "@opspulse.test", "RESPONDER").get("id").asText();
        send(put("/api/v1/teams/" + teamId + "/members/" + userId), admin, null).andExpect(status().isOk());
        // idempotent
        send(put("/api/v1/teams/" + teamId + "/members/" + userId), admin, null).andExpect(status().isOk());
        getAs("/api/v1/teams/" + teamId + "/members", admin)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value(userId));

        send(delete("/api/v1/teams/" + teamId + "/members/" + userId), admin, null).andExpect(status().isNoContent());
        getAs("/api/v1/teams/" + teamId + "/members", admin).andExpect(jsonPath("$.length()").value(0));

        send(patch("/api/v1/teams/" + teamId), admin, Map.of("description", "Owns checkout"))
                .andExpect(jsonPath("$.description").value("Owns checkout"));
        send(delete("/api/v1/teams/" + teamId), admin, null).andExpect(status().isNoContent());
        getAs("/api/v1/teams/" + teamId, admin).andExpect(status().isNotFound());
    }

    @Test
    void duplicateSlugIsAConflict() throws Exception {
        String admin = adminToken();
        String slug = unique("dup");
        send(post("/api/v1/teams"), admin, Map.of("name", "A", "slug", slug)).andExpect(status().isCreated());
        send(post("/api/v1/teams"), admin, Map.of("name", "B", "slug", slug)).andExpect(status().isConflict());
    }

    @Test
    void teamWithServicesCannotBeDeleted() throws Exception {
        String admin = adminToken();
        String teamId = createTeam(admin).get("id").asText();
        createService(admin, teamId);
        send(delete("/api/v1/teams/" + teamId), admin, null).andExpect(status().isConflict());
    }

    @Test
    void validationErrorsListTheFields() throws Exception {
        send(post("/api/v1/users"), adminToken(), Map.of("email", "not-an-email", "password", "short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists())
                .andExpect(jsonPath("$.errors.fullName").exists())
                .andExpect(jsonPath("$.errors.role").exists());
    }

    @Test
    void duplicateEmailIsAConflictRegardlessOfCase() throws Exception {
        String email = unique("dup") + "@opspulse.test";
        createUser(email, "VIEWER");
        send(post("/api/v1/users"), adminToken(), Map.of(
                "email", email.toUpperCase(), "fullName", "Dup", "password", "password-123", "role", "VIEWER"))
                .andExpect(status().isConflict());
    }

    @Test
    void badTimezoneIsRejected() throws Exception {
        send(post("/api/v1/users"), adminToken(), Map.of("email", unique("tz") + "@opspulse.test",
                "fullName", "TZ", "password", "password-123", "role", "VIEWER", "timezone", "Mars/Olympus"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lastActiveAdminCannotBeDemotedOrDeactivated() throws Exception {
        String admin = adminToken();
        String myId = body(getAs("/api/v1/auth/me", admin)).get("id").asText();
        // other tests may have created admins; demote them all except the bootstrap one
        for (var u : body(getAs("/api/v1/users", admin))) {
            if (u.get("role").asText().equals("ADMIN") && u.get("active").asBoolean() && !u.get("id").asText().equals(myId)) {
                send(patch("/api/v1/users/" + u.get("id").asText()), admin, Map.of("role", "VIEWER"));
            }
        }
        send(patch("/api/v1/users/" + myId), admin, Map.of("role", "VIEWER")).andExpect(status().isConflict());
        send(delete("/api/v1/users/" + myId), admin, null).andExpect(status().isConflict());

        // with a second admin, demotion is allowed again
        String second = createUser(unique("admin2") + "@opspulse.test", "ADMIN").get("id").asText();
        send(patch("/api/v1/users/" + second), admin, Map.of("role", "RESPONDER")).andExpect(status().isOk());
    }

    @Test
    void serviceCrudAndFilterByTeam() throws Exception {
        String admin = adminToken();
        String teamId = createTeam(admin).get("id").asText();
        var svc = createService(admin, teamId);
        String id = svc.get("id").asText();
        assertThat(svc.get("status").asText()).isEqualTo("OPERATIONAL");
        assertThat(svc.get("teamId").asText()).isEqualTo(teamId);

        getAs("/api/v1/services?teamId=" + teamId, admin)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[*].id", hasItem(id)));

        send(patch("/api/v1/services/" + id), admin, Map.of("status", "MAINTENANCE"))
                .andExpect(jsonPath("$.status").value("MAINTENANCE"));

        send(post("/api/v1/services"), admin, Map.of("name", "Dup", "serviceKey", svc.get("serviceKey").asText(), "teamId", teamId))
                .andExpect(status().isConflict());
        send(post("/api/v1/services"), admin, Map.of("name", "Bad key", "serviceKey", "Has Spaces", "teamId", teamId))
                .andExpect(status().isBadRequest());

        send(delete("/api/v1/services/" + id), admin, null).andExpect(status().isNoContent());
    }
}
