package com.opspulse.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Entity
@Table(name = "escalation_policies")
@Getter @Setter @NoArgsConstructor
public class EscalationPolicy {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 128)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id")
    private Team team;

    /** How many times to loop back to level 0 after exhausting the ladder. */
    @Column(name = "repeat_count", nullable = false)
    private int repeatCount = 0;

    @OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("level ASC")
    private List<EscalationRule> rules = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    public Optional<EscalationRule> ruleAtLevel(int level) {
        return rules.stream().filter(r -> r.getLevel() == level).findFirst();
    }

    public int maxLevel() {
        return rules.stream().mapToInt(EscalationRule::getLevel).max().orElse(0);
    }

    public void addRule(EscalationRule rule) {
        rule.setPolicy(this);
        rules.add(rule);
        rules.sort(Comparator.comparingInt(EscalationRule::getLevel));
    }
}
