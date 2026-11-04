package com.opspulse.web.dto;

import com.opspulse.domain.entity.UserAccount;
import com.opspulse.domain.enums.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * Request/response records. Entities never leave the service layer:
 * serialising a JPA entity leaks columns (password_hash!), triggers lazy loads
 * outside a transaction, and couples the public API to the table layout.
 */
public final class UserDtos {
    private UserDtos() {}

    public record UserResponse(UUID id, String email, String fullName, Role role,
                               String timezone, boolean active, Instant createdAt) {
        public static UserResponse from(UserAccount u) {
            return new UserResponse(u.getId(), u.getEmail(), u.getFullName(), u.getRole(),
                    u.getTimezone(), u.isActive(), u.getCreatedAt());
        }
    }

    public record CreateUserRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 255) String fullName,
            @NotBlank @Size(min = 8, max = 128) String password,
            @NotNull Role role,
            String timezone) {}

    /** Admin edit. Every field optional: null means "leave unchanged". */
    public record UpdateUserRequest(@Size(min = 1, max = 255) String fullName, Role role,
                                    String timezone, Boolean active) {}

    /** Self-service edit: no role or active flag, a user cannot promote themselves. */
    public record UpdateProfileRequest(@Size(min = 1, max = 255) String fullName, String timezone) {}

    public record ChangePasswordRequest(@NotBlank String currentPassword,
                                        @NotBlank @Size(min = 8, max = 128) String newPassword) {}
}
