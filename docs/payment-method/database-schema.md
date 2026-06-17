# Database Schema — Payment Method

**Stack:** Java 25 · Spring Boot 4.0.6 · Spring Security 7 · PostgreSQL 18 · Flyway · JPA/Hibernate  
**Format:** [DBML](https://dbml.dbdiagram.io/) — paste into [dbdiagram.io](https://dbdiagram.io/) to render  
**Design principles:**
- UUID primary keys (`gen_random_uuid()`) on all entities
- No database ENUM types — lookup table for payment method values
- `payment_method_id` is NOT NULL with a default pointing to the `OTHER` row
- `credit_card_id` on `transactions` is nullable — required only when method is `CREDIT_CARD`
- All existing design constraints from the v1 schema remain in effect
- Schema managed exclusively via Flyway versioned migrations

---

## Schema (DBML)

```dbml
// ============================================================
// Database Schema — Payment Method Feature
// ============================================================
// Stack   : Java 25 · Spring Boot 4.0.6 · Spring Security 7
//           PostgreSQL 18 · Flyway · JPA/Hibernate
// ============================================================
//
// Sections:
//   1. New Lookup Table     — payment_methods
//   2. Modified: transactions       — add payment_method_id, credit_card_id
//   3. Modified: installment_series — add payment_method_id
// ============================================================

// ============================================================
// SECTION 1 — payment_methods LOOKUP TABLE
// Seeded by Flyway migration with seven canonical slugs.
// Never mutated at runtime.
// ============================================================

Table payment_methods {
  id          uuid        [pk, default: `gen_random_uuid()`]
  name        varchar(50) [not null, unique]
  slug        varchar(50) [not null, unique]
  description text
  is_active   boolean     [not null, default: true]
  created_at  timestamptz [not null, default: `now()`]
  updated_at  timestamptz [not null, default: `now()`]

  indexes {
    slug [unique, name: "uidx_payment_methods_slug"]
  }

  Note: "Seed values: CASH, PIX, DEBIT_CARD, CREDIT_CARD, BANK_TRANSFER, BOLETO, OTHER. OTHER is the default for all existing transactions at migration time."
}

// ============================================================
// SECTION 2 — transactions (MODIFIED)
// Two new columns added via ALTER TABLE migration.
// payment_method_id: NOT NULL, defaults to OTHER row at migration.
// credit_card_id: nullable; required only when method = CREDIT_CARD.
// ============================================================

Table transactions {
  // --- existing columns (abbreviated) ---
  id                  uuid        [pk, default: `gen_random_uuid()`]
  user_id             uuid        [not null, ref: > users.id]
  account_id          uuid        [not null, ref: > accounts.id]
  type                varchar(30) [not null]
  status              varchar(30) [not null]
  amount              numeric(19,2) [not null]
  description         varchar(255) [not null]
  notes               text
  competence_date     date        [not null]
  payment_date        date
  cancelled_at        timestamptz
  installment_series_id uuid      [ref: > installment_series.id]
  installment_number  int
  total_installments  int
  is_detached         boolean     [not null, default: false]
  is_early_settlement boolean     [not null, default: false]
  recurrence_rule_id  uuid        [ref: > recurrence_rules.id]
  category_id         uuid        [ref: > categories.id]
  subcategory_id      uuid        [ref: > categories.id]
  transfer_group_id   uuid
  location            varchar(255)
  created_at          timestamptz [not null, default: `now()`]
  updated_at          timestamptz [not null, default: `now()`]

  // --- NEW COLUMNS ---
  payment_method_id   uuid        [not null, ref: > payment_methods.id, note: "Defaults to OTHER row for all existing records at migration time"]
  credit_card_id      uuid        [null,     ref: > credit_cards.id,    note: "Required when payment_method = CREDIT_CARD; null otherwise"]

  indexes {
    user_id                              [name: "idx_transactions_user_id"]
    (user_id, competence_date)           [name: "idx_transactions_user_competence"]
    (user_id, payment_date)              [name: "idx_transactions_user_payment"]
    status                               [name: "idx_transactions_status"]
    payment_method_id                    [name: "idx_transactions_payment_method"]
    credit_card_id                       [name: "idx_transactions_credit_card"]
  }

  Note: "payment_method_id: NOT NULL; existing rows backfilled to OTHER at migration. credit_card_id: nullable FK; non-null only when payment_method = CREDIT_CARD. Constraint enforced at application layer (service), not database level, to produce human-readable errors."
}

// ============================================================
// SECTION 3 — installment_series (MODIFIED)
// One new column added: payment_method_id.
// credit_card_id already exists on this table.
// ============================================================

Table installment_series {
  // --- existing columns (abbreviated) ---
  id                  uuid        [pk, default: `gen_random_uuid()`]
  user_id             uuid        [not null, ref: > users.id]
  account_id          uuid        [not null, ref: > accounts.id]
  credit_card_id      uuid        [null, ref: > credit_cards.id]
  type                varchar(30) [not null]
  description         varchar(255) [not null]
  total_amount        numeric(19,2) [not null]
  total_installments  int         [not null]
  first_payment_date  date        [not null]
  category_id         uuid        [ref: > categories.id]
  subcategory_id      uuid        [ref: > categories.id]
  is_settled          boolean     [not null, default: false]
  settled_at          timestamptz
  created_at          timestamptz [not null, default: `now()`]
  updated_at          timestamptz [not null, default: `now()`]

  // --- NEW COLUMN ---
  payment_method_id   uuid        [not null, ref: > payment_methods.id, note: "Defaults to OTHER row for existing series at migration time"]

  indexes {
    user_id           [name: "idx_installment_series_user_id"]
    payment_method_id [name: "idx_installment_series_payment_method"]
  }

  Note: "payment_method_id is propagated to all generated installment transactions. credit_card_id was already present; now formally tied to payment_method = CREDIT_CARD semantics."
}
```

---

## Migration Plan

| Version | File                                              | Description                                                              |
|---------|---------------------------------------------------|--------------------------------------------------------------------------|
| V14     | `V14__create_payment_methods_table.sql`           | Create `payment_methods` table and seed the seven canonical rows         |
| V15     | `V15__add_payment_method_to_transactions.sql`     | `ALTER TABLE transactions ADD COLUMN payment_method_id uuid NOT NULL DEFAULT <OTHER_ID>`, add `credit_card_id` nullable FK, add indexes |
| V16     | `V16__add_payment_method_to_installment_series.sql` | `ALTER TABLE installment_series ADD COLUMN payment_method_id uuid NOT NULL DEFAULT <OTHER_ID>`, add index |

> **Note:** The `DEFAULT` clause in V15 and V16 uses the UUID of the `OTHER` row inserted
> in V14. The default constraint is dropped after backfill to enforce application-layer
> validation for new records going forward.

---

## Seed Data (V14)

```sql
INSERT INTO payment_methods (id, name, slug, description) VALUES
  (gen_random_uuid(), 'Dinheiro',               'CASH',            'Pagamento em dinheiro físico'),
  (gen_random_uuid(), 'PIX',                    'PIX',             'Pagamento instantâneo via PIX'),
  (gen_random_uuid(), 'Cartão de Débito',       'DEBIT_CARD',      'Cartão de débito com débito imediato na conta'),
  (gen_random_uuid(), 'Cartão de Crédito',      'CREDIT_CARD',     'Cartão de crédito com lançamento em fatura'),
  (gen_random_uuid(), 'Transferência Bancária', 'BANK_TRANSFER',   'TED, DOC ou transferência entre contas'),
  (gen_random_uuid(), 'Boleto Bancário',        'BOLETO',          'Pagamento via boleto bancário'),
  (gen_random_uuid(), 'Outro',                  'OTHER',           'Forma de pagamento não listada acima');
```