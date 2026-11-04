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
