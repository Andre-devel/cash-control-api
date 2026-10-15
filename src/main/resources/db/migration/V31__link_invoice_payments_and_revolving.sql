-- ============================================================
-- V31 — Pagamento de fatura e saldo rotativo ligados à fatura de origem
--
-- transactions.paid_invoice_id: a fatura que um INVOICE_PAYMENT pagou. É o que
-- permite excluir um pagamento lançado errado e devolver o valor à fatura.
--
-- invoice_items.revolving_from_invoice_id: a fatura PARCIAL cujo restante virou
-- este item rotativo. É o que permite o item acompanhar a fatura de origem
-- quando o total ou o pago dela mudam.
--
-- Os dados antigos são ligados pela descrição que o sistema gerava; o que não
-- casar (descrição editada, cartão renomeado) fica sem vínculo.
-- ============================================================

ALTER TABLE transactions
    ADD COLUMN paid_invoice_id UUID REFERENCES invoices(id) ON DELETE SET NULL;
CREATE INDEX idx_transactions_paid_invoice
    ON transactions (paid_invoice_id) WHERE paid_invoice_id IS NOT NULL;

ALTER TABLE invoice_items
    ADD COLUMN revolving_from_invoice_id UUID REFERENCES invoices(id) ON DELETE SET NULL;
CREATE INDEX idx_invoice_items_revolving_from
    ON invoice_items (revolving_from_invoice_id) WHERE revolving_from_invoice_id IS NOT NULL;

-- Pagamentos: "Pagamento de fatura — <cartão> <mês>". Só liga quando a descrição
-- aponta para uma fatura só (dois cartões do usuário com o mesmo nome ficam de fora).
UPDATE transactions t
SET paid_invoice_id = m.invoice_id
FROM (
    SELECT tx.id AS transaction_id, (array_agg(i.id))[1] AS invoice_id
    FROM transactions tx
    JOIN invoices i ON i.user_id = tx.user_id
    JOIN credit_cards c ON c.id = i.credit_card_id
    WHERE tx.type = 'INVOICE_PAYMENT'
      AND tx.description = 'Pagamento de fatura — ' || c.name || ' ' || i.reference_month
    GROUP BY tx.id
    HAVING count(*) = 1
) m
WHERE t.id = m.transaction_id;

-- Rotativos: "Saldo rotativo de <mês>", na fatura seguinte do mesmo cartão.
UPDATE invoice_items ii
SET revolving_from_invoice_id = src.id
FROM invoices dst, invoices src
WHERE ii.invoice_id = dst.id
  AND src.credit_card_id = dst.credit_card_id
  AND ii.is_revolving = TRUE
  AND src.reference_month = substring(ii.description FROM length('Saldo rotativo de ') + 1);
