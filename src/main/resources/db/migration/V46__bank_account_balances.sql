-- ============================================================
-- V46 — Saldo e limite lidos do banco
--
-- GET /accounts da Pluggy devolve balance em toda conta (no cartão, o saldo
-- devedor atual) e, no cartão, creditData com creditLimit e
-- availableCreditLimit. Gravados a cada leitura das contas da conexão, servem
-- à conciliação: o app compara com o saldo e o uso de limite que ele calcula,
-- e o usuário decide se ajusta. Nada é lançado sozinho.
-- Nulos até a primeira leitura e quando a Pluggy não informa.
-- ============================================================

ALTER TABLE bank_connection_accounts
    ADD COLUMN bank_balance     NUMERIC(15, 2),
    ADD COLUMN bank_balance_at  TIMESTAMPTZ,
    ADD COLUMN credit_limit     NUMERIC(15, 2),
    ADD COLUMN credit_available NUMERIC(15, 2);
