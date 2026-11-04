package com.opspulse.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public final class AuthDtos {
    private AuthDtos() {}

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    public record TokenResponse(String accessToken, String refreshToken, String tokenType,
                                long expiresIn, UserDtos.UserResponse user) {
        public static TokenResponse bearer(String access, String refresh, long expiresIn, UserDtos.UserResponse user) {
            return new TokenResponse(access, refresh, "Bearer", expiresIn, user);
        }
    }
}
