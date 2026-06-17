-- V18 — Link invoice_items back to the transaction that originated them.
-- Enables bidirectional sync when transactions are edited, cancelled, or deleted.

ALTER TABLE invoice_items
    ADD COLUMN transaction_id UUID REFERENCES transactions(id) ON DELETE SET NULL;

-- Partial unique index: one active item per transaction (NULLs are excluded)
CREATE UNIQUE INDEX uidx_invoice_items_transaction
    ON invoice_items (transaction_id)
    WHERE transaction_id IS NOT NULL;

CREATE INDEX idx_invoice_items_transaction
    ON invoice_items (transaction_id);