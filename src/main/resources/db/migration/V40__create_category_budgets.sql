-- ============================================================
-- V40 — Orçamento por categoria
--
-- Só o teto fica gravado; o gasto do mês sai sempre das transações, pela
-- competence_date (a compra no cartão conta no mês da compra, não no da fatura).
--
-- month NULL é o teto padrão, que vale todo mês até mudar. Uma linha com
-- month (sempre o dia 1) ajusta só aquele mês e vence o padrão.
-- ============================================================

CREATE TABLE category_budgets (
    id          UUID           NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id     UUID           NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category_id UUID           NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    amount      NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    month       DATE           CHECK (month IS NULL OR EXTRACT(DAY FROM month) = 1),
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    -- Sem NULLS NOT DISTINCT dois tetos padrão (month NULL) da mesma categoria passariam.
    UNIQUE NULLS NOT DISTINCT (user_id, category_id, month)
);
