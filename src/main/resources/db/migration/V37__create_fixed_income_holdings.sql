-- ============================================================
-- V37 — Aplicações e resgates de renda fixa
--
-- A conta INVESTMENT continua sendo o caixa, a preço de custo. Cada aplicação
-- detalha uma parte desse saldo (o principal) e soma por cima o rendimento,
-- calculado na consulta a partir de market_index_rates / treasury_bond_prices
-- (V36). Cada aporte é uma aplicação própria, porque o prazo do IR conta por
-- aporte.
--
-- O resgate grava duas transações que mantêm o caixa coerente: o rendimento
-- (INCOME em Investimentos › Rendimentos) e a transferência do líquido para a
-- conta de destino. Depois do resgate total, o saldo da conta volta ao
-- principal das aplicações que restaram.
-- ============================================================

CREATE TABLE fixed_income_holdings (
    id                        UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id                   UUID          NOT NULL REFERENCES users(id),
    account_id                UUID          NOT NULL REFERENCES accounts(id),
    -- CDB, LCI, LCA, TESOURO_DIRETO, OTHER (enum Java: cada valor tem regra de cálculo).
    kind                      VARCHAR(30)   NOT NULL,
    -- CDI_PERCENT, CDI_SPREAD, PREFIXED, IPCA_SPREAD, SELIC_PERCENT. NULL no Tesouro
    -- (vale o preço de mercado) e em OTHER sem curva (vale o valor informado).
    indexer                   VARCHAR(20),
    -- CDI_PERCENT/SELIC_PERCENT: % do índice (110 = 110%). Demais: % ao ano.
    rate                      NUMERIC(9,4),
    name                      VARCHAR(100)  NOT NULL,
    issuer                    VARCHAR(100),
    tax_exempt                BOOLEAN       NOT NULL DEFAULT FALSE,
    liquidity                 VARCHAR(20)   NOT NULL,
    applied_at                DATE          NOT NULL,
    maturity_date             DATE,
    principal                 NUMERIC(19,2) NOT NULL CHECK (principal > 0),
    remaining_principal       NUMERIC(19,2) NOT NULL CHECK (remaining_principal >= 0),
    -- Tesouro: o título como o CSV do Tesouro o identifica (tipo + vencimento) e a
    -- quantidade ainda em carteira.
    treasury_bond_type        VARCHAR(60),
    treasury_quantity         NUMERIC(19,8),
    -- Valor informado pelo usuário (app do banco). A curva continua a partir dele.
    manual_value              NUMERIC(19,2),
    manual_value_date         DATE,
    -- Transferência que trouxe o dinheiro, quando houve uma (NULL: saldo que já estava na conta).
    funding_transfer_group_id UUID,
    status                    VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    notes                     TEXT,
    deleted_at                TIMESTAMPTZ,
    created_at                TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CHECK (maturity_date IS NULL OR maturity_date > applied_at),
    CHECK ((manual_value IS NULL) = (manual_value_date IS NULL))
);

CREATE INDEX idx_fixed_income_holdings_user_status ON fixed_income_holdings (user_id, status);
CREATE INDEX idx_fixed_income_holdings_account     ON fixed_income_holdings (account_id);

CREATE TABLE fixed_income_redemptions (
    id                   UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    holding_id           UUID          NOT NULL REFERENCES fixed_income_holdings(id),
    redeemed_at          DATE          NOT NULL,
    principal_portion    NUMERIC(19,2) NOT NULL,
    treasury_quantity    NUMERIC(19,8),
    gross                NUMERIC(19,2) NOT NULL,
    income_tax           NUMERIC(19,2) NOT NULL,
    iof                  NUMERIC(19,2) NOT NULL,
    net                  NUMERIC(19,2) NOT NULL,
    -- Rendimento (ou perda) gravado no caixa; NULL quando líquido = principal.
    yield_transaction_id UUID          REFERENCES transactions(id),
    -- Transferência do líquido para outra conta; NULL quando o dinheiro ficou na conta.
    transfer_group_id    UUID,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_fixed_income_redemptions_holding ON fixed_income_redemptions (holding_id);

-- Subcategoria do sistema para o rendimento dos resgates.
INSERT INTO categories (parent_id, name, color, icon, is_default, sort_order)
SELECT parent.id, 'Rendimentos', '#1ABC9C', 'savings', TRUE, 0
FROM categories parent
WHERE parent.name = 'Investimentos' AND parent.user_id IS NULL AND parent.parent_id IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM categories c
      WHERE c.parent_id = parent.id AND c.name = 'Rendimentos' AND c.user_id IS NULL);
