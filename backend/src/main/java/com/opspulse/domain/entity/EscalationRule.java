package com.opspulse.domain.entity;

import com.opspulse.domain.enums.EscalationTargetType;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * One rung of the escalation ladder.
 * delayMinutes is measured from incident creation, not from the previous rung,
 * so a ladder reads naturally: notify at 0, 5, 15 minutes.
 */
@Entity
@Table(name = "escalation_rules")
@Getter @Setter @NoArgsConstructor
public class EscalationRule {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_id")
    private EscalationPolicy policy;

    @Column(nullable = false)
    private int level;

    @Column(name = "delay_minutes", nullable = false)
    private int delayMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 16)
    private EscalationTargetType targetType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_user_id")
    private UserAccount targetUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_team_id")
    private Team targetTeam;
}
