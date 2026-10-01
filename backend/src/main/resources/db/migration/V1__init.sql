-- =====================================================================
-- OpsPulse :: baseline schema
-- Conventions:
--   * status/enum-like columns are VARCHAR + CHECK (not PG ENUM types) --
--     adding a value to a PG ENUM is a migration headache and Hibernate
--     mapping is fiddly. CHECK constraints give the same safety.
--   * every table uses UUID v4 primary keys (gen_random_uuid(), core in PG13+)
--   * timestamps are timestamptz, always. Never naive timestamps in an
--     on-call system that spans regions.
-- =====================================================================

-- ---------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------
CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(255) NOT NULL,
    role          VARCHAR(32)  NOT NULL DEFAULT 'RESPONDER',
    timezone      VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT users_role_chk CHECK (role IN ('ADMIN', 'RESPONDER', 'VIEWER'))
);

CREATE INDEX idx_users_email_lower ON users (lower(email));

-- ---------------------------------------------------------------------
-- teams
-- ---------------------------------------------------------------------
CREATE TABLE teams (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(128) NOT NULL,
    slug        VARCHAR(128) NOT NULL UNIQUE,
    description TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE team_members (
    team_id  UUID        NOT NULL REFERENCES teams (id) ON DELETE CASCADE,
    user_id  UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    added_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (team_id, user_id)
);

CREATE INDEX idx_team_members_user ON team_members (user_id);

-- ---------------------------------------------------------------------
-- escalation policies
--
-- A policy is an ordered ladder of rules. Level 0 fires immediately on
-- incident creation; level N fires delay_minutes after the incident was
-- created, provided it is still un-acknowledged.
-- ---------------------------------------------------------------------
CREATE TABLE escalation_policies (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(128) NOT NULL,
    team_id      UUID         NOT NULL REFERENCES teams (id) ON DELETE CASCADE,
    repeat_count INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT esc_policy_repeat_chk CHECK (repeat_count BETWEEN 0 AND 5)
);

CREATE TABLE escalation_rules (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id      UUID NOT NULL REFERENCES escalation_policies (id) ON DELETE CASCADE,
    level          INT  NOT NULL,
    delay_minutes  INT  NOT NULL,
    target_type    VARCHAR(16) NOT NULL,
    target_user_id UUID REFERENCES users (id) ON DELETE SET NULL,
    target_team_id UUID REFERENCES teams (id) ON DELETE SET NULL,
    CONSTRAINT esc_rule_level_chk  CHECK (level >= 0),
    CONSTRAINT esc_rule_delay_chk  CHECK (delay_minutes >= 0),
    CONSTRAINT esc_rule_target_chk CHECK (target_type IN ('USER', 'TEAM')),
    -- exactly one target must be set, matching target_type
    CONSTRAINT esc_rule_target_xor CHECK (
        (target_type = 'USER' AND target_user_id IS NOT NULL AND target_team_id IS NULL)
     OR (target_type = 'TEAM' AND target_team_id IS NOT NULL AND target_user_id IS NULL)
    ),
    CONSTRAINT esc_rule_level_uk UNIQUE (policy_id, level)
);

-- ---------------------------------------------------------------------
-- services (the things being monitored)
--
-- NOTE: entity is named MonitoredService in Java -- "Service" collides
-- with org.springframework.stereotype.Service and makes every import ugly.
-- ---------------------------------------------------------------------
CREATE TABLE services (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                 VARCHAR(128) NOT NULL,
    service_key          VARCHAR(128) NOT NULL UNIQUE,
    description          TEXT,
    team_id              UUID NOT NULL REFERENCES teams (id),
    escalation_policy_id UUID REFERENCES escalation_policies (id) ON DELETE SET NULL,
    status               VARCHAR(32) NOT NULL DEFAULT 'OPERATIONAL',
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT services_status_chk CHECK (status IN ('OPERATIONAL', 'DEGRADED', 'DOWN', 'MAINTENANCE'))
);

CREATE INDEX idx_services_team ON services (team_id);

-- ---------------------------------------------------------------------
-- api_keys
--
-- Monitoring systems cannot refresh a JWT. They authenticate with a
-- long-lived, service-scoped key. Only the hash is stored -- the plaintext
-- is shown exactly once, at creation.
-- ---------------------------------------------------------------------
CREATE TABLE api_keys (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id   UUID         NOT NULL REFERENCES services (id) ON DELETE CASCADE,
    name         VARCHAR(128) NOT NULL,
    key_prefix   VARCHAR(16)  NOT NULL,
    key_hash     VARCHAR(255) NOT NULL UNIQUE,
    last_used_at TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_api_keys_service ON api_keys (service_id);

-- ---------------------------------------------------------------------
-- incidents
--
-- THE important table. Two things carry the design:
--
-- 1. dedup_key + the partial unique index below. This is what makes the
--    system an incident manager rather than an alert log: the DATABASE
--    guarantees at most one OPEN incident per (service, fingerprint),
--    even under concurrent ingestion from multiple backend replicas.
--    A second concurrent insert fails on the constraint; the ingest path
--    catches that and attaches the alert to the winner instead.
--
-- 2. version -- optimistic locking. Two responders hitting "acknowledge"
--    at the same moment must not both win silently.
-- ---------------------------------------------------------------------
CREATE TABLE incidents (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id               UUID         NOT NULL REFERENCES services (id),
    title                    VARCHAR(512) NOT NULL,
    description              TEXT,
    severity                 VARCHAR(16)  NOT NULL,
    status                   VARCHAR(32)  NOT NULL DEFAULT 'TRIGGERED',
    dedup_key                VARCHAR(255) NOT NULL,
    assigned_user_id         UUID REFERENCES users (id) ON DELETE SET NULL,
    escalation_policy_id     UUID REFERENCES escalation_policies (id) ON DELETE SET NULL,
    current_escalation_level INT          NOT NULL DEFAULT 0,
    escalation_round         INT          NOT NULL DEFAULT 0,
    last_escalated_at        TIMESTAMPTZ,
    alert_count              INT          NOT NULL DEFAULT 1,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    acknowledged_at          TIMESTAMPTZ,
    resolved_at              TIMESTAMPTZ,
    resolved_by_user_id      UUID REFERENCES users (id) ON DELETE SET NULL,
    version                  INT          NOT NULL DEFAULT 0,
    CONSTRAINT incidents_sev_chk    CHECK (severity IN ('SEV1', 'SEV2', 'SEV3', 'SEV4')),
    CONSTRAINT incidents_status_chk CHECK (status IN ('TRIGGERED', 'ACKNOWLEDGED', 'RESOLVED')),
    -- a resolved incident must carry a resolution timestamp, and vice versa
    CONSTRAINT incidents_resolved_chk CHECK (
        (status = 'RESOLVED' AND resolved_at IS NOT NULL)
     OR (status <> 'RESOLVED' AND resolved_at IS NULL)
    )
);

-- The deduplication guarantee. Partial: resolved incidents are excluded,
-- so the same problem recurring next week correctly opens a NEW incident.
CREATE UNIQUE INDEX uq_incidents_open_dedup
    ON incidents (dedup_key)
    WHERE status <> 'RESOLVED';

-- Dashboard query: open incidents, newest first.
CREATE INDEX idx_incidents_open
    ON incidents (created_at DESC)
    WHERE status <> 'RESOLVED';

CREATE INDEX idx_incidents_service_created ON incidents (service_id, created_at DESC);
CREATE INDEX idx_incidents_assignee       ON incidents (assigned_user_id) WHERE status <> 'RESOLVED';

-- Escalation sweeper's driving query -- keep it index-only cheap.
CREATE INDEX idx_incidents_escalation
    ON incidents (last_escalated_at)
    WHERE status = 'TRIGGERED';

-- ---------------------------------------------------------------------
-- alerts (raw ingested signals, high volume)
-- ---------------------------------------------------------------------
CREATE TABLE alerts (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id  UUID         NOT NULL REFERENCES services (id),
    incident_id UUID         REFERENCES incidents (id) ON DELETE SET NULL,
    fingerprint VARCHAR(255) NOT NULL,
    dedup_key   VARCHAR(255) NOT NULL,
    severity    VARCHAR(16)  NOT NULL,
    summary     VARCHAR(512) NOT NULL,
    source      VARCHAR(128) NOT NULL DEFAULT 'api',
    event_type  VARCHAR(16)  NOT NULL DEFAULT 'TRIGGER',
    payload     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    received_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT alerts_sev_chk   CHECK (severity IN ('SEV1', 'SEV2', 'SEV3', 'SEV4')),
    CONSTRAINT alerts_event_chk CHECK (event_type IN ('TRIGGER', 'RESOLVE'))
);

CREATE INDEX idx_alerts_incident ON alerts (incident_id, received_at DESC);
CREATE INDEX idx_alerts_dedup    ON alerts (dedup_key, received_at DESC);

-- ---------------------------------------------------------------------
-- incident_events
--
-- Serves as BOTH the user-facing incident timeline and the audit log.
-- One append-only table, never updated or deleted.
-- actor_user_id IS NULL  =>  the system did it (ingest, escalation job).
-- ---------------------------------------------------------------------
CREATE TABLE incident_events (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id    UUID        NOT NULL REFERENCES incidents (id) ON DELETE CASCADE,
    actor_user_id  UUID        REFERENCES users (id) ON DELETE SET NULL,
    type           VARCHAR(32) NOT NULL,
    message        TEXT,
    payload        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT incident_events_type_chk CHECK (type IN (
        'CREATED', 'ALERT_ATTACHED', 'ACKNOWLEDGED', 'ESCALATED',
        'REASSIGNED', 'NOTE_ADDED', 'SEVERITY_CHANGED', 'RESOLVED',
        'NOTIFICATION_SENT'
    ))
);

CREATE INDEX idx_incident_events_incident ON incident_events (incident_id, created_at);
CREATE INDEX idx_incident_events_actor    ON incident_events (actor_user_id, created_at DESC);

-- ---------------------------------------------------------------------
-- notifications (transactional outbox)
--
-- Rows are inserted in the SAME transaction as the incident change.
-- A separate poller sends them. Consequences:
--   * a rolled-back transaction never sends an email
--   * a crash mid-send loses nothing; the row is still PENDING
--   * retry/backoff is just a next_attempt_at column
-- ---------------------------------------------------------------------
CREATE TABLE notifications (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id       UUID         NOT NULL REFERENCES incidents (id) ON DELETE CASCADE,
    recipient_user_id UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    channel           VARCHAR(16)  NOT NULL DEFAULT 'EMAIL',
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    subject           VARCHAR(512) NOT NULL,
    body              TEXT         NOT NULL,
    attempts          INT          NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at           TIMESTAMPTZ,
    last_error        TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT notif_channel_chk CHECK (channel IN ('EMAIL')),
    CONSTRAINT notif_status_chk  CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'DEAD'))
);

-- Outbox poller drains via: ... WHERE status='PENDING' AND next_attempt_at <= now()
--                               ORDER BY next_attempt_at FOR UPDATE SKIP LOCKED
CREATE INDEX idx_notifications_pending
    ON notifications (next_attempt_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_notifications_incident ON notifications (incident_id);

-- ---------------------------------------------------------------------
-- ShedLock -- stops two backend replicas running the same scheduled job.
-- ---------------------------------------------------------------------
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
