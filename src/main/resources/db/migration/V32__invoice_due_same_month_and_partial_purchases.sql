-- ============================================================
-- V32 — Vencimento no mesmo mês do fechamento e compras de fatura parcial
--
-- (1) O vencimento é o primeiro due_day depois do fechamento. Quando
-- due_day > closing_day ele cai no mesmo mês (fecha 01/11, vence 10/11); até
-- aqui ia sempre para o mês seguinte, 40 dias depois. Só due_date muda: o
-- fechamento e o reference_month (mês do fechamento) já estavam certos, e os
-- itens continuam na mesma fatura. (dias de cartão vão de 1 a 28, sem ajuste
-- de fim de mês.)
--
-- (2) Com o vencimento antecipado, faturas sem pagamento podem já ter vencido.
--
-- (3) Compra de fatura PARCIAL só está paga quando o restante foi quitado na
-- fatura que recebeu o rotativo. Até aqui o pagamento parcial marcava todas
-- como PAGAS. Cobre um nível de rotativo; cadeias mais longas são refeitas
-- pelo serviço no próximo pagamento ou estorno.
-- ============================================================

-- (1)
UPDATE invoices i
SET due_date = make_date(EXTRACT(YEAR FROM i.closing_date)::int,
                         EXTRACT(MONTH FROM i.closing_date)::int,
                         c.due_day)
FROM credit_cards c
WHERE c.id = i.credit_card_id
  AND c.due_day > c.closing_day
  AND i.due_date <> make_date(EXTRACT(YEAR FROM i.closing_date)::int,
                              EXTRACT(MONTH FROM i.closing_date)::int,
                              c.due_day);

-- (2) Mesma regra de InvoiceRepository.markOverdue.
UPDATE invoices
SET status = 'OVERDUE'
WHERE status IN ('OPEN', 'CLOSED')
  AND due_date < CURRENT_DATE;

-- (3)
UPDATE transactions t
SET status = 'PENDING',
    payment_date = NULL
FROM invoice_items ii
JOIN invoices i ON i.id = ii.invoice_id
WHERE ii.transaction_id = t.id
  AND ii.cancelled_at IS NULL
  AND i.status = 'PARTIAL'
  AND t.status = 'PAID'
  AND NOT EXISTS (
      SELECT 1
      FROM invoice_items r
      JOIN invoices next ON next.id = r.invoice_id
      WHERE r.revolving_from_invoice_id = i.id
        AND r.cancelled_at IS NULL
        AND next.status = 'PAID');
