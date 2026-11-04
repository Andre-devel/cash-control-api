-- ============================================================
-- V44 — Histórico de sincronizações com o banco
--
-- Uma linha por leitura de um item da Pluggy: a varredura de hora em hora,
-- os eventos do webhook, o "Atualizar" da tela e o cadastro pelo widget.
-- Antes só o log do container guardava falhas e contagens; aqui o ADMIN as
-- vê no app. A limpeza diária apaga o que passar de app.retention.bank-sync-run-days.
--
-- connection_id não tem FK de propósito: a conexão pode ser apagada (pelo
-- usuário ou porque a Pluggy não a reconhece mais) e o histórico fica, com
-- connector_name e item_id copiados. Uma FK também travaria: a linha é gravada
-- em transação própria enquanto a de fora ainda segura o DELETE da conexão.
-- ============================================================

CREATE TABLE bank_sync_runs (
    id                    UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id               UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    connection_id         UUID,
    item_id               VARCHAR(64)   NOT NULL,
    connector_name        VARCHAR(100),
    -- SCHEDULED, WEBHOOK, MANUAL, REGISTER (texto, como os demais tipos do domínio).
    trigger               VARCHAR(20)   NOT NULL,
    -- Evento do webhook que disparou a leitura (item/updated, transactions/created...).
    webhook_event         VARCHAR(64),
    -- SUCCESS, NO_CHANGE, PARTIAL, ERROR, ITEM_GONE.
    outcome               VARCHAR(20)   NOT NULL,
    started_at            TIMESTAMPTZ   NOT NULL,
    finished_at           TIMESTAMPTZ   NOT NULL,
    item_status           VARCHAR(30),
    item_last_updated_at  TIMESTAMPTZ,
    accounts_read         INTEGER,
    transactions_queued   INTEGER,
    investments_created   INTEGER,
    investments_redeemed  INTEGER,
    investments_failed    INTEGER,
    -- transactions/created: minutos entre a chegada do lançamento na Pluggy e o aviso.
    webhook_delay_minutes BIGINT,
    error_message         VARCHAR(500)
);

CREATE INDEX idx_bank_sync_runs_user_started ON bank_sync_runs (user_id, started_at DESC);
CREATE INDEX idx_bank_sync_runs_started ON bank_sync_runs (started_at);

-- Permissão da tela de sincronizações, dada ao ADMIN como as do V7.
INSERT INTO permissions (name, description, category_id, is_system_perm, is_active)
SELECT 'bank-sync:view', 'View the bank synchronization log of the own connections.', pc.id, TRUE, TRUE
FROM permission_categories pc
WHERE pc.slug = 'AUDIT'
ON CONFLICT (name) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name = 'bank-sync:view'
WHERE r.name = 'ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;
