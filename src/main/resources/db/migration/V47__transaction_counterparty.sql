-- ============================================================
-- V47 — Contraparte do lançamento vinda do banco
--
-- A Pluggy devolve em paymentData quem pagou e quem recebeu (Pix, TED,
-- boleto). Em débito a contraparte é o receiver; em crédito, o payer. Sem
-- nome, vale merchant.businessName. O documento é guardado como a Pluggy
-- manda, já mascarado (***.123.456-**).
-- Nulos no que veio por arquivo e no que foi digitado à mão.
-- ============================================================

ALTER TABLE transactions
    ADD COLUMN counterparty_name     VARCHAR(255),
    ADD COLUMN counterparty_document VARCHAR(20);

ALTER TABLE bank_pending_transactions
    ADD COLUMN counterparty_name     VARCHAR(255),
    ADD COLUMN counterparty_document VARCHAR(20);
