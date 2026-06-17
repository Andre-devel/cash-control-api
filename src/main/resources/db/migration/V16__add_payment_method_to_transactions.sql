-- ============================================================
-- V16 — Add payment_method_id and credit_card_id to transactions
-- Backfills existing rows to OTHER, then enforces NOT NULL.
-- ============================================================

ALTER TABLE transactions
    ADD COLUMN payment_method_id UUID REFERENCES payment_methods(id),
    ADD COLUMN credit_card_id    UUID REFERENCES credit_cards(id);

UPDATE transactions
SET payment_method_id = (SELECT id FROM payment_methods WHERE slug = 'OTHER');

ALTER TABLE transactions
    ALTER COLUMN payment_method_id SET NOT NULL;

CREATE INDEX idx_transactions_payment_method ON transactions (payment_method_id);
CREATE INDEX idx_transactions_credit_card    ON transactions (credit_card_id);
