-- ============================================================
-- V39 — Fila de lançamentos do banco a revisar e Web Push
--
-- O webhook transactions/created da Pluggy põe aqui os lançamentos novos da
-- conta corrente vinculada. Nada vira transação sem confirmação: a fila só
-- guarda a linha do extrato; a classificação (tipo, categoria, apelido) é
-- feita de novo a cada leitura, pelas mesmas regras da prévia do import.
--
-- A linha some quando a chave aparece em transactions (confirmada pela fila
-- ou importada pelo diálogo "Buscar do banco"). A descartada fica, com
-- discarded_at, para o próximo webhook não a trazer de volta.
-- ============================================================

CREATE TABLE bank_pending_transactions (
    id                       UUID           NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id                  UUID           NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    account_id               UUID           NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    -- Mesma chave do import pelo banco: 'plg:' + id da transação na Pluggy.
    external_ref             VARCHAR(64)    NOT NULL,
    date                     DATE           NOT NULL,
    history                  VARCHAR(255)   NOT NULL,
    description              VARCHAR(255)   NOT NULL,
    -- Com sinal: negativo saiu da conta.
    amount                   NUMERIC(19, 2) NOT NULL,
    -- Hash (SHA-256, hex) do token que a notificação leva para o botão Confirmar
    -- funcionar sem sessão. Vale só para esta linha e só até expirar.
    action_token_hash        VARCHAR(64),
    action_token_expires_at  TIMESTAMPTZ,
    created_at               TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    notified_at              TIMESTAMPTZ,
    discarded_at             TIMESTAMPTZ,
    CONSTRAINT uq_bank_pending_transactions_ref UNIQUE (account_id, external_ref)
);

CREATE INDEX idx_bank_pending_transactions_user ON bank_pending_transactions (user_id);

-- Um aparelho que ligou "Notificações neste aparelho". O endpoint é do serviço
-- de push do navegador e identifica o aparelho; p256dh e auth cifram a mensagem
-- só para ele.
CREATE TABLE push_subscriptions (
    id               UUID           NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id          UUID           NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    endpoint         VARCHAR(1024)  NOT NULL,
    p256dh           VARCHAR(255)   NOT NULL,
    auth             VARCHAR(64)    NOT NULL,
    device_label     VARCHAR(100),
    -- Notificação sem valores, para a tela bloqueada.
    hide_amounts     BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    last_success_at  TIMESTAMPTZ,
    CONSTRAINT uq_push_subscriptions_endpoint UNIQUE (endpoint)
);

CREATE INDEX idx_push_subscriptions_user ON push_subscriptions (user_id);
