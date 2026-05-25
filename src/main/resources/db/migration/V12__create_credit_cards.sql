-- ============================================================
-- V12 — Credit Cards, Invoices, and Charges
-- Also adds credit_card_id FK back-reference on installment_series.
-- ============================================================

-- ── Shared Limit Groups ──────────────────────────────────────

CREATE TABLE shared_limit_groups (
    id          UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id     UUID          NOT NULL REFERENCES users(id),
    name        VARCHAR(100)  NOT NULL,
    total_limit NUMERIC(19,2) NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE        INDEX idx_shared_limit_groups_user      ON shared_limit_groups (user_id);
CREATE UNIQUE INDEX uidx_shared_limit_groups_user_name ON shared_limit_groups (user_id, name);

-- ── Credit Cards ─────────────────────────────────────────────

CREATE TABLE credit_cards (
    id                    UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id               UUID          NOT NULL REFERENCES users(id),
    name                  VARCHAR(100)  NOT NULL,
    brand                 VARCHAR(30)   NOT NULL,
    issuer                VARCHAR(100),
    credit_limit          NUMERIC(19,2) NOT NULL,
    closing_day           INT           NOT NULL CHECK (closing_day BETWEEN 1 AND 28),
    due_day               INT           NOT NULL CHECK (due_day BETWEEN 1 AND 28),
    shared_limit_group_id UUID          REFERENCES shared_limit_groups(id),
    archived_at           TIMESTAMPTZ,
    deleted_at            TIMESTAMPTZ,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- Unique card name per user among non-deleted cards
CREATE UNIQUE INDEX uidx_credit_cards_user_name
    ON credit_cards (user_id, name)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_credit_cards_user          ON credit_cards (user_id);
CREATE INDEX idx_credit_cards_shared_group  ON credit_cards (shared_limit_group_id);
CREATE INDEX idx_credit_cards_user_deleted  ON credit_cards (user_id, deleted_at);
CREATE INDEX idx_credit_cards_user_archived ON credit_cards (user_id, archived_at);

-- ── Add credit_card_id to installment_series ─────────────────
-- Now that credit_cards exists, add the card-based installment FK.

ALTER TABLE installment_series
    ADD COLUMN credit_card_id UUID REFERENCES credit_cards(id);

CREATE INDEX idx_installment_series_card ON installment_series (credit_card_id);

-- ── Invoices ─────────────────────────────────────────────────

CREATE TABLE invoices (
    id              UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id         UUID          NOT NULL REFERENCES users(id),
    credit_card_id  UUID          NOT NULL REFERENCES credit_cards(id),
    status          VARCHAR(20)   NOT NULL DEFAULT 'OPEN',
    reference_month VARCHAR(7)    NOT NULL,
    closing_date    DATE          NOT NULL,
    due_date        DATE          NOT NULL,
    total_amount    NUMERIC(19,2) NOT NULL DEFAULT 0,
    paid_amount     NUMERIC(19,2) NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uidx_invoices_card_month  ON invoices (credit_card_id, reference_month);
CREATE        INDEX idx_invoices_user          ON invoices (user_id);
CREATE        INDEX idx_invoices_card          ON invoices (credit_card_id);
CREATE        INDEX idx_invoices_user_due      ON invoices (user_id, due_date);
CREATE        INDEX idx_invoices_user_status   ON invoices (user_id, status);
CREATE        INDEX idx_invoices_due_date      ON invoices (due_date);

-- ── Invoice Items ────────────────────────────────────────────

CREATE TABLE invoice_items (
    id                    UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id               UUID          NOT NULL REFERENCES users(id),
    invoice_id            UUID          NOT NULL REFERENCES invoices(id),
    description           VARCHAR(255)  NOT NULL,
    amount                NUMERIC(19,2) NOT NULL,
    competence_date       DATE          NOT NULL,
    category_id           UUID          REFERENCES categories(id),
    subcategory_id        UUID          REFERENCES categories(id),
    notes                 TEXT,
    installment_series_id UUID          REFERENCES installment_series(id),
    installment_number    INT,
    total_installments    INT,
    is_detached           BOOLEAN       NOT NULL DEFAULT FALSE,
    is_revolving          BOOLEAN       NOT NULL DEFAULT FALSE,
    cancelled_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_invoice_items_user           ON invoice_items (user_id);
CREATE INDEX idx_invoice_items_invoice        ON invoice_items (invoice_id);
CREATE INDEX idx_invoice_items_invoice_date   ON invoice_items (invoice_id, competence_date);
CREATE INDEX idx_invoice_items_series         ON invoice_items (installment_series_id);
CREATE INDEX idx_invoice_items_category       ON invoice_items (category_id);
CREATE INDEX idx_invoice_items_user_cancelled ON invoice_items (user_id, cancelled_at);

-- ── Invoice Item Tags ────────────────────────────────────────

CREATE TABLE invoice_item_tags (
    id              UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    invoice_item_id UUID        NOT NULL REFERENCES invoice_items(id),
    tag_id          UUID        NOT NULL REFERENCES tags(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uidx_invoice_item_tags      ON invoice_item_tags (invoice_item_id, tag_id);
CREATE        INDEX idx_invoice_item_tags_item   ON invoice_item_tags (invoice_item_id);
CREATE        INDEX idx_invoice_item_tags_tag    ON invoice_item_tags (tag_id);
