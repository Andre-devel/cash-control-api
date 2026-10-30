-- ============================================================
-- V38 — Conexões bancárias (Open Finance via Pluggy)
--
-- Uma conexão é um "item" da Pluggy: o consentimento que o usuário deu a um
-- banco pelo widget Pluggy Connect. As contas que a Pluggy devolve para o
-- item ficam espelhadas aqui, e cada uma pode ser vinculada a uma conta
-- (BANK) ou a um cartão (CREDIT) do app — é esse vínculo que diz para onde
-- vão os lançamentos buscados no banco.
--
-- Status e contas são sempre lidos da Pluggy (GET /items, GET /accounts) e
-- nunca do corpo do webhook, que chega sem assinatura.
-- ============================================================

CREATE TABLE bank_connections (
    id              UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id         UUID          NOT NULL REFERENCES users(id),
    -- Só PLUGGY por enquanto; a coluna existe para o item_id ser único por provedor.
    provider        VARCHAR(20)   NOT NULL,
    item_id         VARCHAR(64)   NOT NULL,
    connector_name  VARCHAR(100),
    -- Status do item como a Pluggy devolve (UPDATED, UPDATING, LOGIN_ERROR,
    -- OUTDATED, WAITING_USER_INPUT...). Texto livre: a lista é da Pluggy.
    status          VARCHAR(30)   NOT NULL,
    -- Mensagem do erro da última atualização, quando houver.
    status_detail   VARCHAR(255),
    -- Quando a Pluggy terminou a última atualização com o banco (item.lastUpdatedAt).
    last_synced_at  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_bank_connections_item UNIQUE (provider, item_id)
);

CREATE INDEX idx_bank_connections_user ON bank_connections (user_id);

CREATE TABLE bank_connection_accounts (
    id                    UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    connection_id         UUID          NOT NULL REFERENCES bank_connections(id) ON DELETE CASCADE,
    external_account_id   VARCHAR(64)   NOT NULL,
    kind                  VARCHAR(10)   NOT NULL CHECK (kind IN ('BANK', 'CREDIT')),
    name                  VARCHAR(255)  NOT NULL,
    number                VARCHAR(64),
    account_id            UUID          REFERENCES accounts(id),
    credit_card_id        UUID          REFERENCES credit_cards(id),
    CONSTRAINT uq_bank_connection_accounts_external UNIQUE (connection_id, external_account_id),
    -- Conta corrente só vincula a conta; cartão só vincula a cartão.
    CONSTRAINT ck_bank_connection_accounts_link CHECK (
        (kind = 'BANK' AND credit_card_id IS NULL) OR (kind = 'CREDIT' AND account_id IS NULL))
);

-- Uma conta ou cartão do app recebe lançamentos de uma origem só; duas
-- contas da Pluggy no mesmo destino duplicariam tudo.
CREATE UNIQUE INDEX uidx_bank_connection_accounts_account
    ON bank_connection_accounts (account_id) WHERE account_id IS NOT NULL;
CREATE UNIQUE INDEX uidx_bank_connection_accounts_card
    ON bank_connection_accounts (credit_card_id) WHERE credit_card_id IS NOT NULL;
