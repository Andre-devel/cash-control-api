-- ============================================================
-- V9 — Accounts & Wallets
-- Financial accounts per user with type, currency, lifecycle.
-- Balance is computed at query time from PAID transactions.
-- ============================================================

CREATE TABLE accounts (
    id            UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id       UUID          NOT NULL REFERENCES users(id),
    name          VARCHAR(100)  NOT NULL,
    type          VARCHAR(30)   NOT NULL,
    currency_code CHAR(3)       NOT NULL DEFAULT 'BRL',
    description   VARCHAR(255),
    sort_order    INT           NOT NULL DEFAULT 0,
    archived_at   TIMESTAMPTZ,
    deleted_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- Unique name per user among non-deleted accounts
CREATE UNIQUE INDEX uidx_accounts_user_name
    ON accounts (user_id, name)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_accounts_user         ON accounts (user_id);
CREATE INDEX idx_accounts_type         ON accounts (type);
CREATE INDEX idx_accounts_user_archived ON accounts (user_id, archived_at);
CREATE INDEX idx_accounts_user_deleted  ON accounts (user_id, deleted_at);
