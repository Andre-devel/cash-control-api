-- ============================================================
-- V23 — Chave de origem para importação de faturas de cartão
--
-- Mesmo mecanismo do V21 (transactions), só que escopado por fatura em vez
-- de conta: a fatura do mês é reimportada com frequência (o usuário baixa o
-- PDF de novo, ou importa antes e depois do fechamento) e o external_ref é
-- o hash determinístico da linha do PDF que impede a duplicação.
--
-- Índice parcial pelo mesmo motivo do V21: lançamentos criados à mão ou
-- gerados por parcelamento ficam com NULL e não disputam unicidade.
-- ============================================================

ALTER TABLE invoice_items ADD COLUMN external_ref VARCHAR(64);

CREATE UNIQUE INDEX uidx_invoice_items_external_ref
    ON invoice_items (user_id, invoice_id, external_ref)
    WHERE external_ref IS NOT NULL;
