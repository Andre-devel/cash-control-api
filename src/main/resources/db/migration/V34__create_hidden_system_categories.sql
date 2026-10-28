-- ============================================================
-- V34 — Categoria do sistema oculta por usuário
--
-- categories.is_hidden é uma coluna da própria linha. Nas categorias do
-- usuário isso basta, mas as do sistema (user_id NULL) são compartilhadas:
-- ocultar uma delas gravava a flag na linha de todos. Aqui fica a escolha de
-- cada usuário; is_hidden passa a valer só para as categorias do usuário.
--
-- Não dá para saber quem ocultou uma categoria do sistema antes desta
-- migração, então a flag dessas linhas é zerada sem migrar para a tabela nova.
-- ============================================================

CREATE TABLE hidden_system_categories (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users(id),
    category_id UUID        NOT NULL REFERENCES categories(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uidx_hidden_system_categories_user_category
    ON hidden_system_categories (user_id, category_id);

UPDATE categories SET is_hidden = FALSE WHERE user_id IS NULL AND is_hidden;
