-- Change currency_code from CHAR(3) to VARCHAR(3) to align with Hibernate's
-- default JDBC mapping for Java String fields (Types#VARCHAR).
-- Semantically equivalent for ISO 4217 codes which are always 3 characters.

ALTER TABLE accounts
    ALTER COLUMN currency_code TYPE VARCHAR(3);
