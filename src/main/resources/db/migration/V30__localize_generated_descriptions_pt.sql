-- ============================================================
-- V30 — Descrições geradas pelo sistema em pt-BR
--
-- Saldo inicial, ajuste, transferência, quitação antecipada e saldo rotativo
-- eram gravados com descrição em inglês e aparecem como texto do lançamento na
-- tela. O código passa a gravá-los em pt-BR; aqui os já gravados acompanham.
-- Cada UPDATE casa só a descrição exata que o sistema gerava, então um texto
-- que o usuário editou não é tocado.
-- ============================================================

UPDATE transactions SET description = 'Saldo inicial'
WHERE type = 'MANUAL_ADJUSTMENT' AND description = 'Initial balance';

UPDATE transactions SET description = 'Ajuste manual'
WHERE type = 'MANUAL_ADJUSTMENT' AND description = 'Manual adjustment';

UPDATE transactions SET description = 'Transferência'
WHERE type = 'TRANSFER' AND description = 'Transfer';

UPDATE transactions
SET description = 'Quitação antecipada: ' || substring(description FROM length('Early settlement: ') + 1)
WHERE is_early_settlement = TRUE AND description LIKE 'Early settlement: %';

UPDATE invoice_items
SET description = 'Saldo rotativo de ' || substring(description FROM length('Revolving balance from ') + 1)
WHERE is_revolving = TRUE AND description LIKE 'Revolving balance from %';
