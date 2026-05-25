-- ============================================================
-- V5 — Token Management: email verification and password reset tokens
-- Raw token values are NEVER stored — only cryptographic hashes.
-- Both tables support single-use semantics via consumed_at and invalidated_at.
-- ============================================================

-- Covers initial registration (new_email IS NULL) and
-- email-change verification (new_email IS NOT NULL).
-- At most one active token per user; resend invalidates previous token.
CREATE TABLE email_verification_tokens (
    id             UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id        UUID         NOT NULL REFERENCES users(id),
    token_hash     VARCHAR(255) NOT NULL,
    new_email      VARCHAR(255),
    expires_at     TIMESTAMPTZ  NOT NULL,
    consumed_at    TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uidx_email_verification_tokens_hash   ON email_verification_tokens (token_hash);
CREATE        INDEX idx_email_verification_tokens_user    ON email_verification_tokens (user_id);
-- Active token lookup: WHERE consumed_at IS NULL AND invalidated_at IS NULL
CREATE        INDEX idx_email_verification_active         ON email_verification_tokens (user_id, consumed_at, invalidated_at);
-- Cleanup pipeline: WHERE expires_at < NOW()
CREATE        INDEX idx_email_verification_tokens_expires ON email_verification_tokens (expires_at);

-- Short-TTL (30–60 min); a new reset request invalidates all prior active tokens.
-- On successful reset: token is consumed and users.credentials_updated_at is updated.
CREATE TABLE password_reset_tokens (
    id                UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id           UUID         NOT NULL REFERENCES users(id),
    token_hash        VARCHAR(255) NOT NULL,
    expires_at        TIMESTAMPTZ  NOT NULL,
    consumed_at       TIMESTAMPTZ,
    invalidated_at    TIMESTAMPTZ,
    ip_address_masked VARCHAR(45),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uidx_password_reset_tokens_hash   ON password_reset_tokens (token_hash);
CREATE        INDEX idx_password_reset_tokens_user    ON password_reset_tokens (user_id);
-- Active token lookup: WHERE consumed_at IS NULL AND invalidated_at IS NULL
CREATE        INDEX idx_password_reset_active         ON password_reset_tokens (user_id, consumed_at, invalidated_at);
-- Cleanup pipeline: WHERE expires_at < NOW()
CREATE        INDEX idx_password_reset_tokens_expires ON password_reset_tokens (expires_at);