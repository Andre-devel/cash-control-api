-- ============================================================
-- V24 — Refresh tokens
-- Raw token values are NEVER stored — only cryptographic hashes.
-- Single-use with rotation: consuming a token revokes it and issues a
-- successor in the same family_id. Re-presenting an already revoked token
-- means the cookie leaked, so the whole family is revoked at once.
-- ============================================================

CREATE TABLE refresh_tokens (
    id                   UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id              UUID         NOT NULL REFERENCES users(id),
    token_hash           VARCHAR(255) NOT NULL,
    -- Stable across rotations: identifies one login session end to end.
    family_id            UUID         NOT NULL,
    expires_at           TIMESTAMPTZ  NOT NULL,
    revoked_at           TIMESTAMPTZ,
    ip_address_masked    VARCHAR(45),
    user_agent_truncated VARCHAR(512),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uidx_refresh_tokens_hash    ON refresh_tokens (token_hash);
CREATE        INDEX idx_refresh_tokens_user     ON refresh_tokens (user_id);
-- Reuse detection: revoke every token of a leaked session in one statement
CREATE        INDEX idx_refresh_tokens_family   ON refresh_tokens (family_id);
-- Active token lookup: WHERE user_id = ? AND revoked_at IS NULL
CREATE        INDEX idx_refresh_tokens_active   ON refresh_tokens (user_id, revoked_at);
-- Cleanup pipeline: WHERE expires_at < NOW()
CREATE        INDEX idx_refresh_tokens_expires  ON refresh_tokens (expires_at);
