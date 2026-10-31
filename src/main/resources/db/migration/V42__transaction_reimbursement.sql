-- ============================================================
-- V42 — Reembolso
--
-- Despesa paga agora e devolvida depois (gasto de trabalho, conta dividida):
-- o dinheiro saiu e voltou de verdade, então o saldo continua contando as
-- duas pontas, mas nenhuma das duas é gasto ou receita do usuário.
--
-- reimbursable marca a despesa; ela sai dos gráficos e do orçamento já na
-- marcação, antes de o dinheiro voltar. reimbursed_by_id liga a despesa à
-- entrada que a reembolsou, que também sai deles. Se a entrada for menor que
-- a despesa, a diferença continua contando como gasto; maior é recusado na
-- API. Uma entrada reembolsa uma despesa só (índice único).
--
-- Não serve REFUND: o estorno desconta no mês em que acontece, e o reembolso
-- costuma cair no mês seguinte ao da despesa.
-- ============================================================

ALTER TABLE transactions
    ADD COLUMN reimbursable     BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN reimbursed_by_id UUID REFERENCES transactions(id) ON DELETE SET NULL,
    ADD CONSTRAINT chk_transactions_reimbursed_by_requires_reimbursable
        CHECK (reimbursed_by_id IS NULL OR reimbursable);

CREATE UNIQUE INDEX uidx_transactions_reimbursed_by ON transactions (reimbursed_by_id)
    WHERE reimbursed_by_id IS NOT NULL;
