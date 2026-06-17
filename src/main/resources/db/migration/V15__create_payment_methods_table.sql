-- ============================================================
-- V15 — Payment Methods Lookup Table
-- Stores named payment instruments referenced by transactions
-- and installment series. Seeded with seven standard methods.
-- ============================================================

CREATE TABLE payment_methods (
    id          UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL,
    slug        VARCHAR(50)  NOT NULL,
    description TEXT,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uidx_payment_methods_name ON payment_methods (name);
CREATE UNIQUE INDEX uidx_payment_methods_slug ON payment_methods (slug);

INSERT INTO payment_methods (name, slug, description) VALUES
    ('Cash',          'CASH',          'Physical currency payment'),
    ('Pix',           'PIX',           'Brazilian instant payment system'),
    ('Debit Card',    'DEBIT_CARD',    'Payment via debit card'),
    ('Credit Card',   'CREDIT_CARD',   'Payment via credit card'),
    ('Bank Transfer', 'BANK_TRANSFER', 'Wire or bank transfer'),
    ('Boleto',        'BOLETO',        'Brazilian boleto bancário'),
    ('Other',         'OTHER',         'Other or unspecified payment method');
