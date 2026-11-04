package com.opspulse.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Who may do what. Every rule here is one an attacker would probe. */
class AuthorizationTest extends ApiTestSupport {

    @Test
    void respondersAndViewersCanReadButNotWrite() throws Exception {
        for (String role : new String[]{"RESPONDER", "VIEWER"}) {
            String token = tokenFor(role);
            getAs("/api/v1/teams", token).andExpect(status().isOk());
            getAs("/api/v1/services", token).andExpect(status().isOk());
            getAs("/api/v1/users", token).andExpect(status().isOk());

            send(post("/api/v1/teams"), token, Map.of("name", "Sneaky")).andExpect(status().isForbidden());
            send(post("/api/v1/users"), token, Map.of(
                    "email", unique("x") + "@opspulse.test", "fullName", "X", "password", "password-123", "role", "ADMIN"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
    }

    @Test
    void aUserCannotPromoteThemselvesThroughTheProfileEndpoint() throws Exception {
        String token = tokenFor("RESPONDER");
        // "role" is not a field of UpdateProfileRequest; it is silently ignored
        send(patch("/api/v1/users/me"), token, Map.of("fullName", "Me", "role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("RESPONDER"));
    }

    @Test
    void apiKeysAreAdminOnlyEvenToRead() throws Exception {
        String admin = adminToken();
        String serviceId = createService(admin, createTeam(admin).get("id").asText()).get("id").asText();
        getAs("/api/v1/services/" + serviceId + "/api-keys", tokenFor("RESPONDER")).andExpect(status().isForbidden());
    }

    @Test
    void aJwtIsIgnoredOnTheIngestChain() throws Exception {
        // an admin's JWT must not open the machine-only path
        getAs("/api/v1/alerts/auth-check", adminToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownRoutesAndBadIdsGiveProperErrorsNot500() throws Exception {
        String token = adminToken();
        getAs("/api/v1/teams/not-a-uuid", token).andExpect(status().isBadRequest());
        getAs("/api/v1/teams/00000000-0000-0000-0000-000000000000", token).andExpect(status().isNotFound());
        send(put("/api/v1/teams"), token, Map.of()).andExpect(status().isMethodNotAllowed());
    }
}
