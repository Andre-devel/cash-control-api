-- ============================================================
-- V56 — CPF/CNPJ do titular da conta no banco
-- A Pluggy informa o documento do titular de cada conta (taxNumber). Guardado só
-- com os dígitos, ele reconhece na importação o Pix entre duas contas do próprio
-- usuário (pró-labore, distribuição de lucros), que vira transferência em vez de
-- despesa de um lado e receita do outro.
-- ============================================================

ALTER TABLE bank_connection_accounts ADD COLUMN tax_number VARCHAR(14);
