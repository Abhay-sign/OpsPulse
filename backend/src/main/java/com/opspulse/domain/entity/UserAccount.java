package com.opspulse.domain.entity;

import com.opspulse.domain.enums.Role;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Named UserAccount, not User: "user" is a reserved word in Postgres and
 * `User` collides with Spring Security's own User class in every import list.
 */
@Entity
@Table(name = "users")
@Getter @Setter @NoArgsConstructor
public class UserAccount {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Role role = Role.RESPONDER;

    @Column(nullable = false, length = 64)
    private String timezone = "UTC";

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private Instant updatedAt;

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    @Override public boolean equals(Object o) {
        return o instanceof UserAccount u && id != null && id.equals(u.id);
    }
    @Override public int hashCode() { return getClass().hashCode(); }
}
