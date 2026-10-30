-- ============================================================
-- V35 — Dias de fechamento e vencimento do cartão até 31
--
-- Há emissores que fecham no dia 30 ou 31 (o Inter fecha no 30 e vence no 7). Com o
-- limite de 28 esse cartão não tinha como ser cadastrado, e a aproximação mais próxima
-- empurrava o vencimento para o mês errado. Nos meses mais curtos o dia vira o último do
-- mês (InvoiceCycleCalculator já limita assim o fechamento e o vencimento).
-- ============================================================

ALTER TABLE credit_cards DROP CONSTRAINT credit_cards_closing_day_check;
ALTER TABLE credit_cards DROP CONSTRAINT credit_cards_due_day_check;

ALTER TABLE credit_cards
    ADD CONSTRAINT credit_cards_closing_day_check CHECK (closing_day BETWEEN 1 AND 31),
    ADD CONSTRAINT credit_cards_due_day_check CHECK (due_day BETWEEN 1 AND 31);
