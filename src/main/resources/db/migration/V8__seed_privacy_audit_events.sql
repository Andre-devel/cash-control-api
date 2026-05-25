-- ============================================================
-- V8 — Privacy & Retention Audit Event Types
-- New event types for Phase 11: LGPD, Privacy & Data Retention
-- ============================================================

INSERT INTO audit_event_types (name, slug, description, category, severity, is_active)
VALUES
    ('Token Retention Purge', 'TOKEN_RETENTION_PURGE',
     'Scheduled cleanup of expired/consumed security tokens (password reset and email verification). Recorded once per purge run with count in metadata.',
     'SECURITY', 'NORMAL', true),

    ('User Anonymized', 'USER_ANONYMIZED',
     'User PII fields (email, display_name, password_hash) zeroed per LGPD right-to-erasure pipeline. UUID row and audit trail preserved.',
     'PRIVACY', 'HIGH', true)

ON CONFLICT (slug) DO NOTHING;
