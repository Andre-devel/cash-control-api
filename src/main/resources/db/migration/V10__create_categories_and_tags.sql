-- ============================================================
-- V10 — Categories, Tags, and Category Rules
-- Two-level category hierarchy with system defaults.
-- Tags provide free-form cross-category transaction labeling.
-- Category rules enable auto-categorization at creation time.
-- ============================================================

-- ── Categories ──────────────────────────────────────────────

CREATE TABLE categories (
    id          UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id     UUID         REFERENCES users(id),   -- NULL for system default categories
    parent_id   UUID         REFERENCES categories(id),
    name        VARCHAR(100) NOT NULL,
    color       VARCHAR(7),
    icon        VARCHAR(50),
    sort_order  INT          NOT NULL DEFAULT 0,
    is_default  BOOLEAN      NOT NULL DEFAULT FALSE,
    is_hidden   BOOLEAN      NOT NULL DEFAULT FALSE,
    is_archived BOOLEAN      NOT NULL DEFAULT FALSE,
    archived_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- System root categories: unique name at root level
CREATE UNIQUE INDEX uidx_categories_system_root
    ON categories (name)
    WHERE user_id IS NULL AND parent_id IS NULL;

-- System subcategories: unique name within parent
CREATE UNIQUE INDEX uidx_categories_system_child
    ON categories (parent_id, name)
    WHERE user_id IS NULL AND parent_id IS NOT NULL;

-- User categories: unique name within (user, parent) scope, non-archived
CREATE UNIQUE INDEX uidx_categories_user_name
    ON categories (user_id, parent_id, name)
    WHERE user_id IS NOT NULL AND archived_at IS NULL;

CREATE INDEX idx_categories_user         ON categories (user_id);
CREATE INDEX idx_categories_parent       ON categories (parent_id);
CREATE INDEX idx_categories_default      ON categories (is_default);
CREATE INDEX idx_categories_user_hidden  ON categories (user_id, is_hidden);
CREATE INDEX idx_categories_user_archived ON categories (user_id, is_archived);

-- ── Tags ────────────────────────────────────────────────────

CREATE TABLE tags (
    id         UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users(id),
    name       VARCHAR(50) NOT NULL,
    color      VARCHAR(7),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uidx_tags_user_name ON tags (user_id, name);
CREATE        INDEX idx_tags_user       ON tags (user_id);

-- ── Category Rules ──────────────────────────────────────────

CREATE TABLE category_rules (
    id             UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id        UUID         NOT NULL REFERENCES users(id),
    pattern        VARCHAR(255) NOT NULL,
    category_id    UUID         NOT NULL REFERENCES categories(id),
    subcategory_id UUID         REFERENCES categories(id),
    account_id     UUID         REFERENCES accounts(id),
    priority       INT          NOT NULL DEFAULT 0,
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_category_rules_user            ON category_rules (user_id);
CREATE INDEX idx_category_rules_active_priority ON category_rules (user_id, is_active, priority);
CREATE INDEX idx_category_rules_category        ON category_rules (category_id);
CREATE INDEX idx_category_rules_account         ON category_rules (account_id);
