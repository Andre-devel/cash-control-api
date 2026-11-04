-- Metas de economia. O progresso não é gravado: vem, a cada leitura, do saldo da conta vinculada
-- ou do valor líquido da aplicação vinculada (no máximo um dos dois; sem vínculo, zero).
-- A meta não entra na projeção de saldo.
CREATE TABLE savings_goals (
    id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name          VARCHAR(100)  NOT NULL,
    target_amount NUMERIC(15,2) NOT NULL CHECK (target_amount > 0),
    target_date   DATE,
    account_id    UUID          REFERENCES accounts(id) ON DELETE SET NULL,
    holding_id    UUID          REFERENCES fixed_income_holdings(id) ON DELETE SET NULL,
    icon          VARCHAR(50),
    color         VARCHAR(20),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    archived_at   TIMESTAMPTZ,
    CONSTRAINT chk_savings_goals_single_link CHECK (account_id IS NULL OR holding_id IS NULL)
);

CREATE INDEX idx_savings_goals_user ON savings_goals (user_id);
