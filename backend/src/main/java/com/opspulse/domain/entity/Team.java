package com.opspulse.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "teams")
@Getter @Setter @NoArgsConstructor
public class Team {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, unique = true, length = 128)
    private String slug;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Override public boolean equals(Object o) {
        return o instanceof Team t && id != null && id.equals(t.id);
    }
    @Override public int hashCode() { return getClass().hashCode(); }
}
