-- Escopo pessoa física / jurídica da conta e do cartão. Um lançamento herda o escopo do cartão,
-- quando é compra no cartão, ou o da conta. Categorias e orçamentos continuam únicos.
ALTER TABLE accounts
    ADD COLUMN scope VARCHAR(10) NOT NULL DEFAULT 'PERSONAL',
    ADD CONSTRAINT chk_accounts_scope CHECK (scope IN ('PERSONAL', 'BUSINESS'));

ALTER TABLE credit_cards
    ADD COLUMN scope VARCHAR(10) NOT NULL DEFAULT 'PERSONAL',
    ADD CONSTRAINT chk_credit_cards_scope CHECK (scope IN ('PERSONAL', 'BUSINESS'));
