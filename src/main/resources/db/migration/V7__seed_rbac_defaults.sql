-- ============================================================
-- V7 — RBAC Seed Data: system permissions, roles, and assignments
-- All inserts use ON CONFLICT DO NOTHING for idempotency.
-- is_system_perm = true and is_system_role = true prevent API deletion.
-- ============================================================

-- System permissions — 11 total covering all resource:action authority strings
INSERT INTO permissions (name, description, category_id, is_system_perm, is_active)
SELECT
    v.perm_name,
    v.description,
    pc.id,
    TRUE,
    TRUE
FROM (VALUES
    ('user:create',       'Create new user accounts.',                               'USER_MANAGEMENT'),
    ('user:read',         'Read user account data.',                                 'USER_MANAGEMENT'),
    ('user:update',       'Update user account information and status.',             'USER_MANAGEMENT'),
    ('user:delete',       'Soft-delete user accounts.',                              'USER_MANAGEMENT'),
    ('role:create',       'Create new roles.',                                       'ROLE_MANAGEMENT'),
    ('role:update',       'Update role descriptions and permission assignments.',    'ROLE_MANAGEMENT'),
    ('role:delete',       'Delete roles not currently assigned to any user.',        'ROLE_MANAGEMENT'),
    ('permission:grant',  'Assign permissions to roles or directly to users.',       'PERMISSION_MANAGEMENT'),
    ('permission:revoke', 'Revoke permissions from roles or directly from users.',   'PERMISSION_MANAGEMENT'),
    ('audit:view',        'Access the audit event log and security reports.',        'AUDIT'),
    ('auth:manage',       'Manage authentication security: lockouts, forced re-auth.','AUTH_MANAGEMENT')
) AS v (perm_name, description, category_slug)
JOIN permission_categories pc ON pc.slug = v.category_slug
ON CONFLICT (name) DO NOTHING;

-- System roles
INSERT INTO roles (name, description, is_system_role, is_active)
VALUES
    ('ADMIN', 'System administrator with all built-in permissions.',            TRUE, TRUE),
    ('USER',  'Standard authenticated user with no built-in permissions.', TRUE, TRUE)
ON CONFLICT (name) DO NOTHING;

-- Assign all 11 system permissions to the ADMIN role
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN'
  AND p.name IN (
      'user:create', 'user:read', 'user:update', 'user:delete',
      'role:create', 'role:update', 'role:delete',
      'permission:grant', 'permission:revoke',
      'audit:view', 'auth:manage'
  )
ON CONFLICT (role_id, permission_id) DO NOTHING;