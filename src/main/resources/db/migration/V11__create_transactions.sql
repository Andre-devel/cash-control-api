-- ============================================================
-- V11 — Transactions, Installments, Recurrences, Attachments
-- All account-based financial movements.
-- Credit card charges live in invoice_items (V12).
-- ============================================================

-- ── Installment Series ──────────────────────────────────────
-- Master record for installment payment commitments.
-- credit_card_id FK added in V12 after credit_cards table is created.

CREATE TABLE installment_series (
    id                 UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id            UUID          NOT NULL REFERENCES users(id),
    account_id         UUID          REFERENCES accounts(id),
    type               VARCHAR(30)   NOT NULL,
    description        VARCHAR(255)  NOT NULL,
    total_amount       NUMERIC(19,2) NOT NULL,
    total_installments INT           NOT NULL,
    first_payment_date DATE          NOT NULL,
    category_id        UUID          REFERENCES categories(id),
    subcategory_id     UUID          REFERENCES categories(id),
    is_settled         BOOLEAN       NOT NULL DEFAULT FALSE,
    settled_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_installment_series_user    ON installment_series (user_id);
CREATE INDEX idx_installment_series_account ON installment_series (account_id);
CREATE INDEX idx_installment_series_active  ON installment_series (user_id, is_settled);

-- ── Recurrence Rules ────────────────────────────────────────

CREATE TABLE recurrence_rules (
    id                   UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id              UUID          NOT NULL REFERENCES users(id),
    account_id           UUID          NOT NULL REFERENCES accounts(id),
    type                 VARCHAR(30)   NOT NULL,
    frequency            VARCHAR(20)   NOT NULL,
    status               VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    amount               NUMERIC(19,2) NOT NULL,
    description          VARCHAR(255)  NOT NULL,
    category_id          UUID          REFERENCES categories(id),
    subcategory_id       UUID          REFERENCES categories(id),
    start_date           DATE          NOT NULL,
    end_date             DATE,
    next_occurrence_date DATE,
    paused_at            TIMESTAMPTZ,
    resume_at            TIMESTAMPTZ,
    deleted_at           TIMESTAMPTZ,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_recurrence_rules_user         ON recurrence_rules (user_id);
CREATE INDEX idx_recurrence_rules_account      ON recurrence_rules (account_id);
-- Scheduler query: ACTIVE rules whose next_occurrence_date is due
CREATE INDEX idx_recurrence_rules_scheduler    ON recurrence_rules (status, next_occurrence_date);
CREATE INDEX idx_recurrence_rules_user_status  ON recurrence_rules (user_id, status);
CREATE INDEX idx_recurrence_rules_user_deleted ON recurrence_rules (user_id, deleted_at);

-- ── Transactions ─────────────────────────────────────────────

CREATE TABLE transactions (
    id                    UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id               UUID          NOT NULL REFERENCES users(id),
    account_id            UUID          NOT NULL REFERENCES accounts(id),
    type                  VARCHAR(30)   NOT NULL,
    status                VARCHAR(20)   NOT NULL DEFAULT 'PAID',
    amount                NUMERIC(19,2) NOT NULL,
    description           VARCHAR(255)  NOT NULL,
    notes                 TEXT,
    competence_date       DATE          NOT NULL,
    payment_date          DATE,
    cancelled_at          TIMESTAMPTZ,

    -- Installment-specific fields (NULL for non-installment transactions)
    installment_series_id UUID          REFERENCES installment_series(id),
    installment_number    INT,
    total_installments    INT,
    is_detached           BOOLEAN       NOT NULL DEFAULT FALSE,
    is_early_settlement   BOOLEAN       NOT NULL DEFAULT FALSE,

    -- Recurrence link (NULL for manually created transactions)
    recurrence_rule_id    UUID          REFERENCES recurrence_rules(id),

    -- Category classification (both nullable)
    category_id           UUID          REFERENCES categories(id),
    subcategory_id        UUID          REFERENCES categories(id),

    -- Transfer linking (shared UUID between debit and credit legs)
    transfer_group_id     UUID,

    location              VARCHAR(255),
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_transactions_user            ON transactions (user_id);
CREATE INDEX idx_transactions_account         ON transactions (account_id);
CREATE INDEX idx_transactions_user_competence ON transactions (user_id, competence_date);
CREATE INDEX idx_transactions_user_payment    ON transactions (user_id, payment_date);
CREATE INDEX idx_transactions_user_status     ON transactions (user_id, status);
CREATE INDEX idx_transactions_user_type       ON transactions (user_id, type);
CREATE INDEX idx_transactions_account_status  ON transactions (account_id, status);
CREATE INDEX idx_transactions_installment_series ON transactions (installment_series_id);
CREATE INDEX idx_transactions_recurrence      ON transactions (recurrence_rule_id);
CREATE INDEX idx_transactions_category        ON transactions (category_id);
CREATE INDEX idx_transactions_transfer_group  ON transactions (transfer_group_id);
-- Overdue detection: WHERE payment_date < today AND status = PENDING
CREATE INDEX idx_transactions_overdue_scan    ON transactions (user_id, payment_date, status);

-- ── Transaction Tags ─────────────────────────────────────────

CREATE TABLE transaction_tags (
    id             UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    transaction_id UUID        NOT NULL REFERENCES transactions(id),
    tag_id         UUID        NOT NULL REFERENCES tags(id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uidx_transaction_tags           ON transaction_tags (transaction_id, tag_id);
CREATE        INDEX idx_transaction_tags_transaction ON transaction_tags (transaction_id);
CREATE        INDEX idx_transaction_tags_tag         ON transaction_tags (tag_id);

-- ── Attachments ──────────────────────────────────────────────

CREATE TABLE attachments (
    id                UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id           UUID         NOT NULL REFERENCES users(id),
    transaction_id    UUID         NOT NULL REFERENCES transactions(id),
    original_filename VARCHAR(255) NOT NULL,
    mime_type         VARCHAR(100) NOT NULL,
    file_size_bytes   BIGINT       NOT NULL,
    storage_key       VARCHAR(500) NOT NULL,
    deleted_at        TIMESTAMPTZ,
    uploaded_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uidx_attachments_storage_key       ON attachments (storage_key);
CREATE        INDEX idx_attachments_user               ON attachments (user_id);
CREATE        INDEX idx_attachments_transaction        ON attachments (transaction_id);
CREATE        INDEX idx_attachments_transaction_active ON attachments (transaction_id, deleted_at);
CREATE        INDEX idx_attachments_user_deleted       ON attachments (user_id, deleted_at);
