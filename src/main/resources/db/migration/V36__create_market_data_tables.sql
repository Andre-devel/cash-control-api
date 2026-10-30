-- ============================================================
-- V36 — Índices de mercado e preços do Tesouro Direto
--
-- Dados públicos e globais (sem user_id), base do cálculo das aplicações de
-- renda fixa. Um job os sincroniza a partir do SGS do Banco Central (CDI,
-- Selic, IPCA) e do CSV de preços e taxas do Tesouro Transparente.
--
-- A chave é natural de propósito: a sincronização regrava a mesma data com
-- upsert, sem duplicar, e pode ser repetida a qualquer momento.
-- ============================================================

CREATE TABLE market_index_rates (
    market_index VARCHAR(20)   NOT NULL,
    rate_date    DATE          NOT NULL,
    -- CDI e Selic: % ao dia útil (0.050788). IPCA: % no mês, datado no dia 1.
    value        NUMERIC(12,8) NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (market_index, rate_date)
);

CREATE TABLE treasury_bond_prices (
    -- Como o CSV traz: "Tesouro Selic", "Tesouro IPCA+ com Juros Semestrais".
    bond_type     VARCHAR(60)   NOT NULL,
    maturity_date DATE          NOT NULL,
    base_date     DATE          NOT NULL,
    -- Taxas em % ao ano; PU em reais por título.
    buy_rate      NUMERIC(8,4)  NOT NULL,
    sell_rate     NUMERIC(8,4)  NOT NULL,
    pu_buy        NUMERIC(19,6) NOT NULL,
    pu_sell       NUMERIC(19,6) NOT NULL,
    pu_base       NUMERIC(19,6) NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (bond_type, maturity_date, base_date)
);

-- A sincronização incremental parte da maior base_date já gravada.
CREATE INDEX idx_treasury_bond_prices_base_date ON treasury_bond_prices (base_date);
