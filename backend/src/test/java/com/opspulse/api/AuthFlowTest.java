package com.opspulse.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthFlowTest extends ApiTestSupport {

    @Test
    void loginReturnsTokenPairAndMeWorks() throws Exception {
        var tokens = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        assertThat(tokens.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(tokens.get("expiresIn").asLong()).isEqualTo(900);
        assertThat(tokens.get("user").has("passwordHash")).isFalse();

        getAs("/api/v1/auth/me", tokens.get("accessToken").asText())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(ADMIN_EMAIL))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void emailIsCaseInsensitive() throws Exception {
        login(ADMIN_EMAIL.toUpperCase(), ADMIN_PASSWORD);
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSameAnswer() throws Exception {
        var wrongPw = loginRaw(ADMIN_EMAIL, "nope-nope-nope").andExpect(status().isUnauthorized());
        var noUser = loginRaw("ghost@opspulse.test", "nope-nope-nope").andExpect(status().isUnauthorized());
        assertThat(body(wrongPw).get("detail")).isEqualTo(body(noUser).get("detail"));
    }

    @Test
    void protectedEndpointsRequireAToken() throws Exception {
        getAs("/api/v1/users", null)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        getAs("/api/v1/users", "not.a.jwt").andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRotatesAndOldRefreshTokenCannotBeReused() throws Exception {
        String refresh1 = login(ADMIN_EMAIL, ADMIN_PASSWORD).get("refreshToken").asText();

        var second = body(refresh(refresh1).andExpect(status().isOk()));
        String refresh2 = second.get("refreshToken").asText();
        assertThat(refresh2).isNotEqualTo(refresh1);

        // replaying the consumed token is refused -- this is what exposes a stolen token
        refresh(refresh1).andExpect(status().isUnauthorized());
        // the rotated one still works
        refresh(refresh2).andExpect(status().isOk());
    }

    @Test
    void refreshTokenIsNotAcceptedAsAnAccessToken() throws Exception {
        String refresh = login(ADMIN_EMAIL, ADMIN_PASSWORD).get("refreshToken").asText();
        getAs("/api/v1/auth/me", refresh).andExpect(status().isUnauthorized());
    }

    @Test
    void accessTokenIsNotAcceptedAsARefreshToken() throws Exception {
        String access = adminToken();
        refresh(access).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        String refresh = login(ADMIN_EMAIL, ADMIN_PASSWORD).get("refreshToken").asText();
        send(post("/api/v1/auth/logout"), null, Map.of("refreshToken", refresh)).andExpect(status().isNoContent());
        refresh(refresh).andExpect(status().isUnauthorized());
    }

    @Test
    void deactivatedUserCannotLogInOrRefresh() throws Exception {
        String email = unique("leaver") + "@opspulse.test";
        String id = createUser(email, "RESPONDER").get("id").asText();
        String refresh = login(email, "password-123").get("refreshToken").asText();

        send(delete("/api/v1/users/" + id), adminToken(), null).andExpect(status().isNoContent());

        loginRaw(email, "password-123").andExpect(status().isUnauthorized());
        refresh(refresh).andExpect(status().isUnauthorized());
    }

    @Test
    void userCanChangeOwnPasswordOnlyWithTheCurrentOne() throws Exception {
        String email = unique("pw") + "@opspulse.test";
        createUser(email, "VIEWER");
        String token = login(email, "password-123").get("accessToken").asText();

        send(post("/api/v1/users/me/password"), token, Map.of("currentPassword", "wrong-one", "newPassword", "new-password-1"))
                .andExpect(status().isUnauthorized());
        send(post("/api/v1/users/me/password"), token, Map.of("currentPassword", "password-123", "newPassword", "new-password-1"))
                .andExpect(status().isNoContent());
        login(email, "new-password-1");
    }

    private org.springframework.test.web.servlet.ResultActions loginRaw(String email, String pw) throws Exception {
        return send(post("/api/v1/auth/login"), null, Map.of("email", email, "password", pw));
    }

    private org.springframework.test.web.servlet.ResultActions refresh(String token) throws Exception {
        return send(post("/api/v1/auth/refresh"), null, Map.of("refreshToken", token));
    }
}
