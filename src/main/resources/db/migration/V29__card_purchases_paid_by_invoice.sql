-- ============================================================
-- V29 — Compra no cartão é quitada pelo pagamento da fatura
--
-- Até aqui uma compra no cartão podia nascer PAGA e debitar a conta, e o
-- pagamento da fatura (EXPENSE) debitava de novo. A regra nova:
--
--   * a compra (transactions.credit_card_id preenchido) nunca entra no saldo
--     da conta — o saldo passa a filtrar credit_card_id IS NULL;
--   * o dinheiro sai da conta pelo INVOICE_PAYMENT, gravado negativo como a
--     perna de origem de uma transferência, fora de receitas e despesas;
--   * a compra fica PENDENTE até a fatura dela ser paga, e aí vira PAGA —
--     é assim que ela entra no dashboard, uma vez só.
--
-- Esta migração alinha os dados já gravados com essa regra.
-- ============================================================

-- (1) Pagamentos de fatura antigos: eram EXPENSE positivos com a descrição
-- montada por CreditCardServiceImpl.payInvoice.
UPDATE transactions
SET type = 'INVOICE_PAYMENT',
    amount = -amount,
    description = 'Pagamento de fatura — ' || substring(description FROM length('Invoice payment — ') + 1)
WHERE type = 'EXPENSE'
  AND credit_card_id IS NULL
  AND description LIKE 'Invoice payment — %';

-- (2) Compras de faturas já pagas (total ou parcialmente) viram PAGAS. A data
-- real do pagamento não foi registrada na compra; o vencimento da fatura é a
-- melhor aproximação e põe o gasto no mês em que a fatura foi paga.
UPDATE transactions t
SET status = 'PAID',
    payment_date = i.due_date
FROM invoice_items ii
JOIN invoices i ON i.id = ii.invoice_id
WHERE ii.transaction_id = t.id
  AND ii.cancelled_at IS NULL
  AND i.status IN ('PAID', 'PARTIAL')
  AND t.status IN ('PENDING', 'OVERDUE');

-- (3) Compras de faturas ainda não pagas voltam a PENDENTE sem data de
-- pagamento: PAGA aqui era pagamento fora da fatura, e uma data de pagamento
-- faria o job de vencidas marcá-las como VENCIDAS.
UPDATE transactions t
SET status = 'PENDING',
    payment_date = NULL
FROM invoice_items ii
JOIN invoices i ON i.id = ii.invoice_id
WHERE ii.transaction_id = t.id
  AND ii.cancelled_at IS NULL
  AND i.status NOT IN ('PAID', 'PARTIAL')
  AND t.status IN ('PAID', 'OVERDUE', 'PENDING');
