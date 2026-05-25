-- ============================================================
-- V1 — Lookup Tables
-- All categorical values are data-driven lookup rows; no DB ENUMs.
-- Standard shape: id · name (unique) · slug (unique) ·
--                 description · is_active · created_at · updated_at
-- Seeded by V2; extended via API or future migrations.
-- ============================================================

CREATE TABLE account_statuses (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    name        VARCHAR(50) NOT NULL CONSTRAINT uq_account_statuses_name UNIQUE,
    slug        VARCHAR(50) NOT NULL,
    description TEXT,
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uidx_account_statuses_slug ON account_statuses (slug);

CREATE TABLE auth_origins (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    name        VARCHAR(50) NOT NULL CONSTRAINT uq_auth_origins_name UNIQUE,
    slug        VARCHAR(50) NOT NULL,
    description TEXT,
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uidx_auth_origins_slug ON auth_origins (slug);

CREATE TABLE oauth_providers (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    name        VARCHAR(50) NOT NULL CONSTRAINT uq_oauth_providers_name UNIQUE,
    slug        VARCHAR(50) NOT NULL,
    description TEXT,
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uidx_oauth_providers_slug ON oauth_providers (slug);

CREATE TABLE lockout_types (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    name        VARCHAR(50) NOT NULL CONSTRAINT uq_lockout_types_name UNIQUE,
    slug        VARCHAR(50) NOT NULL,
    description TEXT,
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uidx_lockout_types_slug ON lockout_types (slug);

CREATE TABLE permission_categories (
    id          UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    name        VARCHAR(100) NOT NULL CONSTRAINT uq_permission_categories_name UNIQUE,
    slug        VARCHAR(100) NOT NULL,
    description TEXT,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uidx_permission_categories_slug ON permission_categories (slug);

CREATE TABLE authentication_methods (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    name        VARCHAR(50) NOT NULL CONSTRAINT uq_authentication_methods_name UNIQUE,
    slug        VARCHAR(50) NOT NULL,
    description TEXT,
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uidx_authentication_methods_slug ON authentication_methods (slug);

-- audit_event_types has two extra columns beyond the standard shape:
--   category : AUTHENTICATION | AUTHORIZATION | ACCOUNT | TOKEN | SECURITY | PRIVACY
--   severity : NORMAL | HIGH | CRITICAL — drives SIEM alerting thresholds
CREATE TABLE audit_event_types (
    id          UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    name        VARCHAR(100) NOT NULL CONSTRAINT uq_audit_event_types_name UNIQUE,
    slug        VARCHAR(100) NOT NULL,
    description TEXT,
    category    VARCHAR(50)  NOT NULL,
    severity    VARCHAR(20)  NOT NULL DEFAULT 'NORMAL',
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uidx_audit_event_types_slug              ON audit_event_types (slug);
CREATE        INDEX idx_audit_event_types_category_severity  ON audit_event_types (category, severity);

CREATE TABLE audit_outcomes (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    name        VARCHAR(20) NOT NULL CONSTRAINT uq_audit_outcomes_name UNIQUE,
    slug        VARCHAR(20) NOT NULL,
    description TEXT,
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uidx_audit_outcomes_slug ON audit_outcomes (slug);