-- ============================================================
-- V4 — RBAC: roles, permissions, and join tables
-- Role and permission names are immutable after creation —
-- they appear verbatim in @PreAuthorize expressions and JWT claims.
-- Unique indexes (not inline constraints) are named per schema spec.
-- ============================================================

CREATE TABLE roles (
    id             UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    name           VARCHAR(100) NOT NULL,
    description    TEXT,
    is_system_role BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by_id  UUID         REFERENCES users(id),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uidx_roles_name     ON roles (name);
CREATE        INDEX idx_roles_is_active ON roles (is_active);

CREATE TABLE permissions (
    id             UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    name           VARCHAR(100) NOT NULL,
    description    TEXT,
    category_id    UUID         REFERENCES permission_categories(id),
    is_system_perm BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uidx_permissions_name    ON permissions (name);
CREATE        INDEX idx_permissions_category  ON permissions (category_id);
CREATE        INDEX idx_permissions_is_active ON permissions (is_active);

-- Explicit join table; no cascade deletes; idempotent via unique constraint
CREATE TABLE role_permissions (
    id             UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    role_id        UUID        NOT NULL REFERENCES roles(id),
    permission_id  UUID        NOT NULL REFERENCES permissions(id),
    granted_by_id  UUID        REFERENCES users(id),
    granted_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uidx_role_permissions           ON role_permissions (role_id, permission_id);
CREATE        INDEX idx_role_permissions_role        ON role_permissions (role_id);
CREATE        INDEX idx_role_permissions_permission  ON role_permissions (permission_id);

-- expires_at supports future time-bounded role grants without schema changes
CREATE TABLE user_roles (
    id             UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id        UUID        NOT NULL REFERENCES users(id),
    role_id        UUID        NOT NULL REFERENCES roles(id),
    granted_by_id  UUID        REFERENCES users(id),
    granted_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at     TIMESTAMPTZ
);
CREATE UNIQUE INDEX uidx_user_roles      ON user_roles (user_id, role_id);
CREATE        INDEX idx_user_roles_user  ON user_roles (user_id);
CREATE        INDEX idx_user_roles_role  ON user_roles (role_id);

-- Direct user-level permission overrides outside any role
CREATE TABLE user_permissions (
    id             UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id        UUID        NOT NULL REFERENCES users(id),
    permission_id  UUID        NOT NULL REFERENCES permissions(id),
    granted_by_id  UUID        REFERENCES users(id),
    granted_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at     TIMESTAMPTZ
);
CREATE UNIQUE INDEX uidx_user_permissions           ON user_permissions (user_id, permission_id);
CREATE        INDEX idx_user_permissions_user        ON user_permissions (user_id);
CREATE        INDEX idx_user_permissions_permission  ON user_permissions (permission_id);