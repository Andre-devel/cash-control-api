-- ============================================================
-- V48 — Compras do cartão na fila de revisão
--
-- A fila (V39) passa a receber também as compras da fatura atual do cartão
-- vinculado. Cada linha é de uma conta corrente ou de um cartão, nunca dos
-- dois. No cartão a chave é a do FaturaRowHasher (a mesma da prévia da
-- fatura), não 'plg:' + id, e o que a confirmação precisa para gerar as
-- parcelas seguintes com a chave certa fica junto: o final do cartão da
-- seção, o ordinal da linha, a parcela, o total e a data da compra.
--
-- No cartão, date é a data do lançamento na fatura; purchase_date é a da
-- compra, que a parcela repete mês a mês e que entra na chave.
-- ============================================================

ALTER TABLE bank_pending_transactions
    ALTER COLUMN account_id DROP NOT NULL,
    ADD COLUMN credit_card_id     UUID REFERENCES credit_cards(id) ON DELETE CASCADE,
    ADD COLUMN installment_number INTEGER,
    ADD COLUMN total_installments INTEGER,
    ADD COLUMN purchase_date      DATE,
    -- Final do cartão da seção da fatura (titular, virtual, adicional): entra na chave.
    ADD COLUMN card_last4         VARCHAR(4),
    -- Posição entre linhas idênticas da fatura, como a prévia devolve.
    ADD COLUMN ordinal            INTEGER,
    ADD CONSTRAINT ck_bank_pending_transactions_target
        CHECK ((account_id IS NULL) <> (credit_card_id IS NULL));

CREATE UNIQUE INDEX uq_bank_pending_transactions_card_ref
    ON bank_pending_transactions (credit_card_id, external_ref)
    WHERE credit_card_id IS NOT NULL;
