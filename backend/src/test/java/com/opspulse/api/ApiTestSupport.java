package com.opspulse.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspulse.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Helpers for HTTP-level tests. Requests go through the real filter chains,
 * real JWTs and the real database -- nothing about security is mocked, because
 * mocked security tests are exactly the ones that pass while prod is open.
 *
 * Tests share one database, so every helper generates unique emails/slugs
 * instead of relying on cleanup between tests.
 */
public abstract class ApiTestSupport extends AbstractIntegrationTest {

    protected static final String ADMIN_EMAIL = "admin@opspulse.test";
    protected static final String ADMIN_PASSWORD = "admin-password-123";

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;

    protected String adminToken() throws Exception {
        return login(ADMIN_EMAIL, ADMIN_PASSWORD).get("accessToken").asText();
    }

    protected JsonNode login(String email, String password) throws Exception {
        return body(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()));
    }

    /** Creates a user through the API as admin and returns an access token for them. */
    protected String tokenFor(String role) throws Exception {
        String email = unique(role.toLowerCase()) + "@opspulse.test";
        createUser(email, role);
        return login(email, "password-123").get("accessToken").asText();
    }

    /** A user plus their token, for tests that need the id too (assignee checks, actor names). */
    protected record Actor(String id, String token) {}

    protected Actor actor(String role) throws Exception {
        String email = unique(role.toLowerCase()) + "@opspulse.test";
        String id = createUser(email, role).get("id").asText();
        return new Actor(id, login(email, "password-123").get("accessToken").asText());
    }

    protected JsonNode createUser(String email, String role) throws Exception {
        return body(send(post("/api/v1/users"), adminToken(), Map.of(
                "email", email, "fullName", "Test " + role, "password", "password-123", "role", role))
                .andExpect(status().isCreated()));
    }

    protected JsonNode createTeam(String token) throws Exception {
        return body(send(post("/api/v1/teams"), token, Map.of("name", "Team " + unique("t")))
                .andExpect(status().isCreated()));
    }

    protected JsonNode createService(String token, String teamId) throws Exception {
        return body(send(post("/api/v1/services"), token, Map.of(
                "name", "Checkout API", "serviceKey", unique("checkout"), "teamId", teamId))
                .andExpect(status().isCreated()));
    }

    /** A fresh team + service + API key. Each test gets its own service, so dedup keys never collide. */
    protected Sender newSender() throws Exception {
        String admin = adminToken();
        var svc = createService(admin, createTeam(admin).get("id").asText());
        var key = body(send(post("/api/v1/services/" + svc.get("id").asText() + "/api-keys"), admin, Map.of("name", "test"))
                .andExpect(status().isCreated()));
        return new Sender(UUID.fromString(svc.get("id").asText()), svc.get("serviceKey").asText(),
                UUID.fromString(key.get("key").get("id").asText()), key.get("plaintextKey").asText());
    }

    protected record Sender(UUID serviceId, String serviceKey, UUID keyId, String apiKey) {
        public com.opspulse.security.ApiKeyPrincipal principal() {
            return new com.opspulse.security.ApiKeyPrincipal(keyId, serviceId, serviceKey);
        }
    }

    protected ResultActions alert(Sender s, Map<String, ?> body) throws Exception {
        return mvc.perform(post("/api/v1/alerts").header("X-API-Key", s.apiKey())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    protected static Map<String, Object> trigger(String fingerprint, String severity) {
        return Map.of("fingerprint", fingerprint, "severity", severity, "summary", "Problem: " + fingerprint,
                "source", "prometheus");
    }

    protected static Map<String, Object> resolveOf(String fingerprint) {
        return Map.of("fingerprint", fingerprint, "eventType", "RESOLVE");
    }

    protected ResultActions send(MockHttpServletRequestBuilder req, String token, Object body) throws Exception {
        if (token != null) req.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(req);
    }

    protected ResultActions getAs(String url, String token) throws Exception {
        return send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url), token, null);
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    protected static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
