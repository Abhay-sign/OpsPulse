package com.opspulse.api;

import com.opspulse.security.ApiKeyHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiKeyTest extends ApiTestSupport {

    @Autowired JdbcTemplate jdbc;

    @Test
    void keyIsShownOnceAndOnlyItsHashIsStored() throws Exception {
        String admin = adminToken();
        var svc = createService(admin, createTeam(admin).get("id").asText());
        String serviceId = svc.get("id").asText();

        var created = body(send(post("/api/v1/services/" + serviceId + "/api-keys"), admin, Map.of("name", "prometheus"))
                .andExpect(status().isCreated()));
        String plaintext = created.get("plaintextKey").asText();
        assertThat(plaintext).startsWith("op_");

        String stored = jdbc.queryForObject("SELECT key_hash FROM api_keys WHERE id = ?::uuid",
                String.class, created.get("key").get("id").asText());
        assertThat(stored).isEqualTo(ApiKeyHasher.hash(plaintext)).doesNotContain(plaintext);

        // listing never includes the secret again
        getAs("/api/v1/services/" + serviceId + "/api-keys", admin)
                .andExpect(jsonPath("$[0].keyPrefix").value(plaintext.substring(0, 10)))
                .andExpect(jsonPath("$[0].plaintextKey").doesNotExist());

        mvc.perform(get("/api/v1/alerts/auth-check").header("X-API-Key", plaintext))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceId").value(serviceId))
                .andExpect(jsonPath("$.serviceKey").value(svc.get("serviceKey").asText()));
    }

    @Test
    void revokedOrMissingOrWrongKeyIsRejected() throws Exception {
        String admin = adminToken();
        String serviceId = createService(admin, createTeam(admin).get("id").asText()).get("id").asText();
        var created = body(send(post("/api/v1/services/" + serviceId + "/api-keys"), admin, Map.of("name", "k")));
        String plaintext = created.get("plaintextKey").asText();
        String keyId = created.get("key").get("id").asText();

        mvc.perform(get("/api/v1/alerts/auth-check")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/alerts/auth-check").header("X-API-Key", "op_made_up")).andExpect(status().isUnauthorized());

        send(delete("/api/v1/services/" + serviceId + "/api-keys/" + keyId), admin, null).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/alerts/auth-check").header("X-API-Key", plaintext)).andExpect(status().isUnauthorized());
    }

    @Test
    void anApiKeyOpensNothingOutsideTheIngestPath() throws Exception {
        String admin = adminToken();
        String serviceId = createService(admin, createTeam(admin).get("id").asText()).get("id").asText();
        String plaintext = body(send(post("/api/v1/services/" + serviceId + "/api-keys"), admin, Map.of("name", "k")))
                .get("plaintextKey").asText();

        mvc.perform(get("/api/v1/services").header("X-API-Key", plaintext)).andExpect(status().isUnauthorized());
    }

    @Test
    void lastUsedIsRecorded() throws Exception {
        String admin = adminToken();
        String serviceId = createService(admin, createTeam(admin).get("id").asText()).get("id").asText();
        var created = body(send(post("/api/v1/services/" + serviceId + "/api-keys"), admin, Map.of("name", "k")));
        assertThat(created.get("key").get("lastUsedAt").isNull()).isTrue();

        mvc.perform(get("/api/v1/alerts/auth-check").header("X-API-Key", created.get("plaintextKey").asText()));
        getAs("/api/v1/services/" + serviceId + "/api-keys", admin).andExpect(jsonPath("$[0].lastUsedAt").isNotEmpty());
    }
}
