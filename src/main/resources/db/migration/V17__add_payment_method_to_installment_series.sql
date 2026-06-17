-- ============================================================
-- V17 — Add payment_method_id to installment_series
-- Backfills existing rows to OTHER, then enforces NOT NULL.
-- ============================================================

ALTER TABLE installment_series
    ADD COLUMN payment_method_id UUID REFERENCES payment_methods(id);

UPDATE installment_series
SET payment_method_id = (SELECT id FROM payment_methods WHERE slug = 'OTHER');

ALTER TABLE installment_series
    ALTER COLUMN payment_method_id SET NOT NULL;

CREATE INDEX idx_installment_series_payment_method ON installment_series (payment_method_id);
