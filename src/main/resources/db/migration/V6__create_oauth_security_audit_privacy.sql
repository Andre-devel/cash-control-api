-- ============================================================
-- V6 — OAuth2, Brute Force, Audit, and Privacy
-- Tables: oauth_accounts, login_attempts, account_lockouts,
--         audit_logs (append-only, NO updated_at),
--         user_consents, mfa_configurations (inactive scaffold)
-- ============================================================

-- Provider-to-user linkage; soft-unlink retains audit history
CREATE TABLE oauth_accounts (
    id               UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id          UUID         NOT NULL REFERENCES users(id),
    provider_id      UUID         NOT NULL REFERENCES oauth_providers(id),
    provider_user_id VARCHAR(255) NOT NULL,
    provider_email   VARCHAR(255),
    display_name     VARCHAR(100),
    linked_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    unlinked_at      TIMESTAMPTZ,
    last_used_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
-- Primary OAuth2 resolution: find user by provider + provider_user_id
CREATE UNIQUE INDEX uidx_oauth_accounts_provider_uid ON oauth_accounts (provider_id, provider_user_id);
CREATE        INDEX idx_oauth_accounts_user           ON oauth_accounts (user_id);
-- Active link lookup: WHERE unlinked_at IS NULL
CREATE        INDEX idx_oauth_accounts_active_link    ON oauth_accounts (user_id, provider_id, unlinked_at);

-- Append-only log of all authentication attempts.
-- user_id is NULLABLE: unknown-email attempts are recorded for IP forensics
-- without confirming email existence (anti-enumeration).
-- failure_context is internal only — NEVER returned to callers.
CREATE TABLE login_attempts (
    id                   UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id              UUID         REFERENCES users(id),
    auth_method_id       UUID         REFERENCES authentication_methods(id),
    ip_address_masked    VARCHAR(45)  NOT NULL,
    user_agent_truncated VARCHAR(512),
    was_successful       BOOLEAN      NOT NULL,
    failure_context      VARCHAR(50),
    correlation_id       UUID         NOT NULL,
    attempted_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE        INDEX idx_login_attempts_user              ON login_attempts (user_id);
-- Failed attempt count for lockout threshold evaluation
CREATE        INDEX idx_login_attempts_user_outcome_time ON login_attempts (user_id, was_successful, attempted_at);
CREATE        INDEX idx_login_attempts_ip                ON login_attempts (ip_address_masked);
-- Rate limiting and credential-stuffing detection by source IP
CREATE        INDEX idx_login_attempts_ip_time           ON login_attempts (ip_address_masked, attempted_at);
CREATE        INDEX idx_login_attempts_time              ON login_attempts (attempted_at);
CREATE        INDEX idx_login_attempts_correlation       ON login_attempts (correlation_id);

-- Historical lockout log; current lockout state is also denormalized on users for fast reads.
-- MANUAL lockouts persist until admin action; AUTOMATIC lockouts have expires_at.
CREATE TABLE account_lockouts (
    id              UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id         UUID        NOT NULL REFERENCES users(id),
    lockout_type_id UUID        NOT NULL REFERENCES lockout_types(id),
    reason          TEXT,
    locked_by_id    UUID        REFERENCES users(id),
    locked_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ,
    unlocked_at     TIMESTAMPTZ,
    unlocked_by_id  UUID        REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE        INDEX idx_account_lockouts_user   ON account_lockouts (user_id);
-- Current lockout: WHERE unlocked_at IS NULL
CREATE        INDEX idx_account_lockouts_active ON account_lockouts (user_id, unlocked_at);
CREATE        INDEX idx_account_lockouts_time   ON account_lockouts (locked_at);
CREATE        INDEX idx_account_lockouts_type   ON account_lockouts (lockout_type_id);

-- Append-only structured audit log.
-- NO updated_at column — enforces immutability at the schema level.
-- actor/target FKs are non-cascading: soft-deleted users retain their UUID rows.
-- metadata JSONB is sanitized by AuditMetadataSanitizer before persistence.
CREATE TABLE audit_logs (
    id                   UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    event_type_id        UUID        NOT NULL REFERENCES audit_event_types(id),
    outcome_id           UUID        NOT NULL REFERENCES audit_outcomes(id),
    actor_user_id        UUID        REFERENCES users(id),
    target_user_id       UUID        REFERENCES users(id),
    ip_address_masked    VARCHAR(45),
    user_agent_truncated VARCHAR(512),
    correlation_id       UUID        NOT NULL,
    metadata             JSONB,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE        INDEX idx_audit_logs_event_type  ON audit_logs (event_type_id);
CREATE        INDEX idx_audit_logs_outcome     ON audit_logs (outcome_id);
CREATE        INDEX idx_audit_logs_actor       ON audit_logs (actor_user_id);
CREATE        INDEX idx_audit_logs_target      ON audit_logs (target_user_id);
-- Per-user activity timeline: reverse chronological (US-5.5)
CREATE        INDEX idx_audit_logs_target_time ON audit_logs (target_user_id, created_at);
-- Admin filter: events of a type within a time range
CREATE        INDEX idx_audit_logs_type_time   ON audit_logs (event_type_id, created_at);
CREATE        INDEX idx_audit_logs_actor_time  ON audit_logs (actor_user_id, created_at);
-- Distributed tracing: join all entries from a single request
CREATE        INDEX idx_audit_logs_correlation ON audit_logs (correlation_id);
-- Retention pipeline: WHERE created_at < now() - retention_interval
CREATE        INDEX idx_audit_logs_time        ON audit_logs (created_at);

-- LGPD consent tracking per user per document version.
-- Append-only: prior consent rows are never deleted — they form the LGPD audit trail.
CREATE TABLE user_consents (
    id                   UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id              UUID         NOT NULL REFERENCES users(id),
    consent_version      VARCHAR(20)  NOT NULL,
    accepted_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    ip_address_masked    VARCHAR(45),
    user_agent_truncated VARCHAR(512),
    revoked_at           TIMESTAMPTZ,
    revocation_reason    TEXT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE        INDEX idx_user_consents_user         ON user_consents (user_id);
CREATE        INDEX idx_user_consents_user_version ON user_consents (user_id, consent_version);
-- Current consent: WHERE revoked_at IS NULL ORDER BY accepted_at DESC
CREATE        INDEX idx_user_consents_active       ON user_consents (user_id, revoked_at);
CREATE        INDEX idx_user_consents_time         ON user_consents (accepted_at);

-- Future-proof MFA scaffold (inactive in current release).
-- Schema is present so MFA enrollment can be added without a structural migration.
CREATE TABLE mfa_configurations (
    id                UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id           UUID         NOT NULL REFERENCES users(id),
    method_id         UUID         NOT NULL REFERENCES authentication_methods(id),
    secret_hash       VARCHAR(255),
    is_enabled        BOOLEAN      NOT NULL DEFAULT FALSE,
    verified_at       TIMESTAMPTZ,
    last_used_at      TIMESTAMPTZ,
    backup_codes_hash VARCHAR(255),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uidx_mfa_configurations_user_method ON mfa_configurations (user_id, method_id);
CREATE        INDEX idx_mfa_configurations_user         ON mfa_configurations (user_id);
CREATE        INDEX idx_mfa_configurations_enabled      ON mfa_configurations (is_enabled);