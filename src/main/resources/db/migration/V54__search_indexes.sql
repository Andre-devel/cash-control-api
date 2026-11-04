-- ============================================================
-- V54 — Busca global
-- Índices trigram para o LIKE com curinga dos dois lados usado pela busca
-- (GET /api/v1/search). A busca compara lower(coluna) LIKE '%termo%', então os
-- índices são sobre a mesma expressão. pg_trgm é extensão "trusted" (PG 13+):
-- o dono do banco a cria sem superusuário.
-- ============================================================

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_transactions_description_trgm
    ON transactions USING gin (lower(description) gin_trgm_ops);

CREATE INDEX idx_transactions_counterparty_name_trgm
    ON transactions USING gin (lower(counterparty_name) gin_trgm_ops);
