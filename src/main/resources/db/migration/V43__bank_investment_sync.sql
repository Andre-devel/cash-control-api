-- ============================================================
-- V43 — Investimentos lidos do banco (Pluggy)
--
-- external_id é o id do investimento na Pluggy. Com ele a varredura de hora
-- em hora atualiza a mesma aplicação em vez de criar outra. Aplicação sem ele
-- é a cadastrada à mão. Um id vale uma aplicação viva por usuário.
--
-- sync_adjustment_id é o ajuste que mantém o saldo da conta de investimento
-- igual ao principal aplicado no banco. É sempre o mesmo lançamento,
-- reescrito a cada varredura, para a diferença passageira (a aplicação chega
-- antes da transferência que a pagou) não virar uma fila de ajustes.
-- ============================================================

ALTER TABLE fixed_income_holdings
    ADD COLUMN external_id VARCHAR(64);

CREATE UNIQUE INDEX uidx_fixed_income_holdings_external
    ON fixed_income_holdings (user_id, external_id)
    WHERE external_id IS NOT NULL AND deleted_at IS NULL;

ALTER TABLE accounts
    ADD COLUMN sync_adjustment_id UUID REFERENCES transactions(id) ON DELETE SET NULL;
