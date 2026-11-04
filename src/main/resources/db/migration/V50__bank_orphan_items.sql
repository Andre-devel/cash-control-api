-- Itens da Pluggy que o webhook anunciou e que não viraram conexão aqui (o cadastro falhou).
-- A API da Pluggy não lista itens por clientUserId, então este é o único registro deles; sem
-- isso o item fica na Pluggy consumindo consentimento sem aparecer no app. Sai daqui quando vira
-- conexão ou quando o usuário apaga o item na Pluggy.
CREATE TABLE bank_orphan_items (
    item_id        VARCHAR(64)  PRIMARY KEY,
    user_id        UUID         REFERENCES users(id) ON DELETE CASCADE,
    connector_name VARCHAR(100),
    first_seen_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_bank_orphan_items_user ON bank_orphan_items (user_id);
