-- ============================================================
-- V45 — Vencimento do consentimento do Open Finance
--
-- A Pluggy devolve consentExpiresAt no item (12 meses depois da conexão).
-- Gravado a cada leitura do item, serve ao aviso diário de consentimento
-- perto de vencer e à linha "Consentimento até" na tela de conexões.
-- Nulo quando a Pluggy não informa (conector sem Open Finance).
-- ============================================================

ALTER TABLE bank_connections ADD COLUMN consent_expires_at TIMESTAMPTZ;
