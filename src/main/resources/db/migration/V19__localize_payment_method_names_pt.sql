-- ============================================================
-- V19 — Localize payment method names to Portuguese
-- ============================================================

UPDATE payment_methods SET name = 'Dinheiro'              WHERE slug = 'CASH';
UPDATE payment_methods SET name = 'Pix'                   WHERE slug = 'PIX';
UPDATE payment_methods SET name = 'Cartão de débito'      WHERE slug = 'DEBIT_CARD';
UPDATE payment_methods SET name = 'Cartão de crédito'     WHERE slug = 'CREDIT_CARD';
UPDATE payment_methods SET name = 'Transferência bancária' WHERE slug = 'BANK_TRANSFER';
UPDATE payment_methods SET name = 'Boleto'                WHERE slug = 'BOLETO';
UPDATE payment_methods SET name = 'Outro'                 WHERE slug = 'OTHER';
