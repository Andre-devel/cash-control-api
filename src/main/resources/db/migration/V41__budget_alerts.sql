-- ============================================================
-- V41 — Alertas de orçamento por push
--
-- Cada aparelho escolhe se recebe o alerta (separado do aviso de lançamento
-- do banco). budget_alerts_sent garante um aviso por faixa (80% e 100%), por
-- categoria e por mês: a chave primária faz o INSERT ... ON CONFLICT DO NOTHING
-- decidir quem envia, mesmo com duas verificações ao mesmo tempo.
-- ============================================================

ALTER TABLE push_subscriptions ADD COLUMN budget_alerts BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE budget_alerts_sent (
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category_id UUID        NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    -- Dia 1 do mês do gasto.
    month       DATE        NOT NULL,
    threshold   SMALLINT    NOT NULL CHECK (threshold IN (80, 100)),
    sent_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, category_id, month, threshold)
);
