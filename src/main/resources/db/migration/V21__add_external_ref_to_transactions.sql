-- ============================================================
-- V21 — Chave de origem para importação de extratos
--
-- Extratos bancários são exportados por período, e períodos se sobrepõem:
-- reimportar um recorte maior traria de volta lançamentos já gravados.
-- external_ref é o hash determinístico da linha do extrato (data, histórico,
-- descrição, valor e ordinal dentro do grupo de linhas idênticas do dia),
-- calculado no import e usado para descartar o que já entrou.
--
-- O índice é parcial de propósito: transações criadas à mão ficam com NULL e
-- não disputam unicidade entre si — no Postgres vários NULL nunca colidem,
-- mas o índice parcial ainda evita carregá-las na estrutura à toa.
-- ============================================================

ALTER TABLE transactions ADD COLUMN external_ref VARCHAR(64);

CREATE UNIQUE INDEX uidx_transactions_external_ref
    ON transactions (user_id, account_id, external_ref)
    WHERE external_ref IS NOT NULL;
