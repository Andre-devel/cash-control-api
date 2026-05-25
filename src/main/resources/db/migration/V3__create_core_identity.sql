-- ============================================================
-- V3 — Core Identity: users table
-- References lookup tables seeded in V1/V2.
-- credentials_updated_at is the lightweight JWT invalidation anchor.
-- Soft-delete via deleted_at preserves audit trail referential integrity.
-- ============================================================

CREATE TABLE users (
    id                     UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    email                  VARCHAR(255) NOT NULL,
    password_hash          VARCHAR(255),
    display_name           VARCHAR(100),
    account_status_id      UUID         NOT NULL REFERENCES account_statuses(id),
    auth_origin_id         UUID         NOT NULL REFERENCES auth_origins(id),
    email_verified_at      TIMESTAMPTZ,
    failed_login_attempts  INT          NOT NULL DEFAULT 0,
    lockout_expires_at     TIMESTAMPTZ,
    lockout_type_id        UUID         REFERENCES lockout_types(id),
    lockout_reason         TEXT,
    last_login_at          TIMESTAMPTZ,
    credentials_updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    consent_accepted_at    TIMESTAMPTZ,
    consent_version        VARCHAR(20),
    deleted_at             TIMESTAMPTZ,
    anonymized_at          TIMESTAMPTZ,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Login-path lookup: email + soft-delete check (most frequently used index)
CREATE UNIQUE INDEX uidx_users_email         ON users (email);
CREATE        INDEX idx_users_account_status ON users (account_status_id);
CREATE        INDEX idx_users_auth_origin    ON users (auth_origin_id);
-- Composite for login path: WHERE email = ? AND deleted_at IS NULL
CREATE        INDEX idx_users_email_deleted  ON users (email, deleted_at);
-- Admin list: filter active users by status excluding soft-deleted
CREATE        INDEX idx_users_status_deleted ON users (account_status_id, deleted_at);
CREATE        INDEX idx_users_last_login     ON users (last_login_at);
-- Retention pipeline: WHERE deleted_at IS NOT NULL AND deleted_at < retention_cutoff
CREATE        INDEX idx_users_deleted_at     ON users (deleted_at);