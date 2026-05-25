-- ============================================================
-- V2 — Seed Data for Lookup Tables
-- All inserts use ON CONFLICT DO NOTHING for idempotency;
-- safe to re-apply in test contexts without duplicate-key errors.
-- ============================================================

-- account_statuses
INSERT INTO account_statuses (name, slug, description, is_active)
VALUES
    ('Active',               'ACTIVE',               'Account is active and can authenticate.',                              TRUE),
    ('Inactive',             'INACTIVE',             'Account has been disabled by an administrator.',                       TRUE),
    ('Locked',               'LOCKED',               'Account is temporarily or permanently locked due to failed attempts.', TRUE),
    ('Pending Verification', 'PENDING_VERIFICATION', 'Account created but email address not yet verified.',                  TRUE)
ON CONFLICT (slug) DO NOTHING;

-- auth_origins
INSERT INTO auth_origins (name, slug, description, is_active)
VALUES
    ('Local',  'LOCAL',  'Account created with email and password.',                         TRUE),
    ('Google', 'GOOGLE', 'Account created via Google OAuth2.',                               TRUE),
    ('Mixed',  'MIXED',  'Account has both local credentials and a linked Google identity.', TRUE)
ON CONFLICT (slug) DO NOTHING;

-- oauth_providers
INSERT INTO oauth_providers (name, slug, description, is_active)
VALUES
    ('Google', 'GOOGLE', 'Google OAuth2 identity provider.', TRUE)
ON CONFLICT (slug) DO NOTHING;

-- lockout_types
INSERT INTO lockout_types (name, slug, description, is_active)
VALUES
    ('Automatic', 'AUTOMATIC', 'System-triggered lockout after failed attempt threshold; has configurable expiry.', TRUE),
    ('Manual',    'MANUAL',    'Admin-applied lockout; permanent until explicitly unlocked by an administrator.',    TRUE)
ON CONFLICT (slug) DO NOTHING;

-- permission_categories
INSERT INTO permission_categories (name, slug, description, is_active)
VALUES
    ('User Management',       'USER_MANAGEMENT',       'Permissions governing user account lifecycle operations.',     TRUE),
    ('Role Management',       'ROLE_MANAGEMENT',       'Permissions governing role creation and modification.',        TRUE),
    ('Permission Management', 'PERMISSION_MANAGEMENT', 'Permissions governing permission assignment and revocation.',  TRUE),
    ('Audit',                 'AUDIT',                 'Permissions governing access to audit logs and reports.',      TRUE),
    ('Auth Management',       'AUTH_MANAGEMENT',       'Permissions governing authentication security operations.',    TRUE)
ON CONFLICT (slug) DO NOTHING;

-- authentication_methods
INSERT INTO authentication_methods (name, slug, description, is_active)
VALUES
    ('Password',      'PASSWORD',      'Standard email and password authentication.',               TRUE),
    ('Google OAuth2', 'GOOGLE_OAUTH2', 'Authentication via Google OAuth2 Authorization Code flow.', TRUE),
    ('MFA TOTP',      'MFA_TOTP',      'Time-based one-time password (reserved for future MFA).',  TRUE)
ON CONFLICT (slug) DO NOTHING;

-- audit_event_types — full taxonomy with category and severity
INSERT INTO audit_event_types (name, slug, description, category, severity, is_active)
VALUES
    -- AUTHENTICATION category
    ('User Registered',           'USER_REGISTERED',          'New local account created via registration flow.',                            'AUTHENTICATION', 'NORMAL',   TRUE),
    ('User Registered Google',    'USER_REGISTERED_GOOGLE',   'New account created via Google OAuth2 flow.',                                 'AUTHENTICATION', 'NORMAL',   TRUE),
    ('Account Linked Google',     'ACCOUNT_LINKED_GOOGLE',    'Existing local account linked to a Google identity.',                         'AUTHENTICATION', 'NORMAL',   TRUE),
    ('Auth Success',              'AUTH_SUCCESS',             'Successful authentication.',                                                   'AUTHENTICATION', 'NORMAL',   TRUE),
    ('Auth Failure',              'AUTH_FAILURE',             'Failed authentication attempt.',                                               'AUTHENTICATION', 'HIGH',     TRUE),
    ('Auth Logout',               'AUTH_LOGOUT',              'User explicitly logged out.',                                                  'AUTHENTICATION', 'NORMAL',   TRUE),
    ('Email Verified',            'EMAIL_VERIFIED',           'User email address successfully verified.',                                    'AUTHENTICATION', 'NORMAL',   TRUE),
    -- ACCOUNT category
    ('Account Locked',            'ACCOUNT_LOCKED',           'Account locked after exceeding failed login attempt threshold.',               'ACCOUNT',        'HIGH',     TRUE),
    ('Account Unlocked',          'ACCOUNT_UNLOCKED',         'Account unlocked by admin action or automatic expiry.',                        'ACCOUNT',        'NORMAL',   TRUE),
    ('User Created',              'USER_CREATED',             'User account created directly by an administrator.',                           'ACCOUNT',        'NORMAL',   TRUE),
    ('User Disabled',             'USER_DISABLED',            'User account deactivated by an administrator.',                                'ACCOUNT',        'HIGH',     TRUE),
    ('User Activated',            'USER_ACTIVATED',           'User account re-activated by an administrator.',                               'ACCOUNT',        'NORMAL',   TRUE),
    ('User Deleted',              'USER_DELETED',             'User account soft-deleted.',                                                   'ACCOUNT',        'HIGH',     TRUE),
    ('Password Changed',          'PASSWORD_CHANGED',         'User changed their own password while authenticated.',                         'ACCOUNT',        'HIGH',     TRUE),
    ('Password Reset Requested',  'PASSWORD_RESET_REQUESTED', 'Password reset email requested by user.',                                      'ACCOUNT',        'NORMAL',   TRUE),
    ('Password Reset Completed',  'PASSWORD_RESET_COMPLETED', 'Password reset successfully completed via reset token.',                       'ACCOUNT',        'HIGH',     TRUE),
    ('Consent Accepted',          'CONSENT_ACCEPTED',         'User accepted the data processing consent at registration.',                   'ACCOUNT',        'NORMAL',   TRUE),
    ('Provider Unlinked',         'PROVIDER_UNLINKED',        'OAuth2 provider account unlinked from user by the user.',                      'ACCOUNT',        'NORMAL',   TRUE),
    -- TOKEN category
    ('Credentials Invalidated',   'CREDENTIALS_INVALIDATED',  'credentials_updated_at updated; all previously issued JWTs are invalidated.', 'TOKEN',          'CRITICAL', TRUE),
    -- AUTHORIZATION category
    ('Role Assigned',             'ROLE_ASSIGNED',            'Role assigned to a user by an administrator.',                                 'AUTHORIZATION',  'HIGH',     TRUE),
    ('Role Removed',              'ROLE_REMOVED',             'Role removed from a user by an administrator.',                                'AUTHORIZATION',  'HIGH',     TRUE),
    ('Role Created',              'ROLE_CREATED',             'New role created by an administrator.',                                        'AUTHORIZATION',  'NORMAL',   TRUE),
    ('Permission Granted',        'PERMISSION_GRANTED',       'Permission granted to a role or directly to a user.',                          'AUTHORIZATION',  'HIGH',     TRUE),
    ('Permission Revoked',        'PERMISSION_REVOKED',       'Permission revoked from a role or directly from a user.',                      'AUTHORIZATION',  'HIGH',     TRUE)
ON CONFLICT (slug) DO NOTHING;

-- audit_outcomes
INSERT INTO audit_outcomes (name, slug, description, is_active)
VALUES
    ('Success', 'SUCCESS', 'The operation completed successfully.',  TRUE),
    ('Failure', 'FAILURE', 'The operation failed or was rejected.',  TRUE)
ON CONFLICT (slug) DO NOTHING;