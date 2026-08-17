-- ============================================================
-- V25 — Refresh token audit event types
-- ============================================================

INSERT INTO audit_event_types (name, slug, description, category, severity, is_active)
VALUES
    ('Token Refreshed', 'TOKEN_REFRESHED',
     'A refresh token was exchanged for a new access token. The presented token is revoked and a successor is issued in the same family.',
     'SECURITY', 'NORMAL', true),

    ('Refresh Token Reuse Detected', 'REFRESH_TOKEN_REUSE_DETECTED',
     'An already revoked refresh token was presented, which indicates the cookie leaked. Every token in the family is revoked and the session is terminated.',
     'SECURITY', 'HIGH', true)

ON CONFLICT (slug) DO NOTHING;
