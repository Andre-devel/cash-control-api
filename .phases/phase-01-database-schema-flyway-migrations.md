## Phase 1 — Database Schema (Flyway Migrations)

**Objective:** Produce the complete, validated PostgreSQL schema via versioned Flyway migrations. All tables, indexes, constraints, and seed data must be in place before any entity or repository code is written.

**Dependencies:** Phase 0 complete.

**Complexity:** Medium

### Phase 1.1 — Flyway Baseline Configuration

**Implementation Tasks:**

- [x] Create `src/main/resources/db/migration/` directory
- [x] Confirm Flyway configuration: `locations = classpath:db/migration`, `validateOnMigrate = true`, `outOfOrder = false`
- [x] Configure `baselineOnMigrate: false`
- [x] Ensure `spring.jpa.hibernate.ddl-auto=validate`

**Acceptance Criteria:**
- [x] Application startup fails if a migration file is tampered (checksum mismatch)
- [x] Flyway `flyway_schema_history` table is created automatically on first run

**Automated Tests:**
- [x] `FlywayMigrationTest` — asserts applied migration count equals the number of V*.sql files

---

### Phase 1.2 — V1: Account & Wallet Schema

**File:** `V1__create_accounts.sql`

**Implementation Tasks:**

- [x] Create `accounts` table:
  - `id UUID PK DEFAULT gen_random_uuid()`
  - `user_id UUID NOT NULL`
  - `name VARCHAR(100) NOT NULL`
  - `type VARCHAR(30) NOT NULL` — `CHECKING`, `SAVINGS`, `CASH`, `VIRTUAL_WALLET`, `INTERNATIONAL`, `JOINT`, `INVESTMENT`
  - `currency_code CHAR(3) NOT NULL DEFAULT 'BRL'`
  - `description VARCHAR(255)`
  - `display_order INT NOT NULL DEFAULT 0`
  - `archived_at TIMESTAMP WITH TIME ZONE`
  - `deleted_at TIMESTAMP WITH TIME ZONE`
  - `created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()`
  - `updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()`
- [x] Add unique index: `(user_id, name)` WHERE `deleted_at IS NULL`
- [x] Add index on `user_id`
- [x] Add index on `archived_at`

**Acceptance Criteria:**
- [x] `accounts` table created with all columns, constraints, and indexes
- [x] Migration applies cleanly on a fresh PostgreSQL 18 instance

**Automated Tests:**
- [x] `AccountsSchemaMigrationTest` — asserts table and all indexes exist via `DatabaseMetaData`

---

### Phase 1.3 — V2: Category & Tag Schema

**File:** `V2__create_categories_and_tags.sql`

**Implementation Tasks:**

- [x] Create `categories` table:
  - `id UUID PK DEFAULT gen_random_uuid()`
  - `user_id UUID` — NULL for system defaults
  - `parent_id UUID REFERENCES categories(id)`
  - `name VARCHAR(100) NOT NULL`
  - `color CHAR(7)` — hex color code
  - `icon VARCHAR(100)`
  - `display_order INT NOT NULL DEFAULT 0`
  - `is_default BOOLEAN NOT NULL DEFAULT false`
  - `is_hidden BOOLEAN NOT NULL DEFAULT false`
  - `archived_at TIMESTAMP WITH TIME ZONE`
  - `created_at`, `updated_at`
- [x] Add unique index: `(user_id, parent_id, name)` WHERE `archived_at IS NULL`
- [x] Add index on `user_id`, `parent_id`
- [x] Create `tags` table:
  - `id UUID PK`, `user_id UUID NOT NULL`, `name VARCHAR(50) NOT NULL`
  - Unique index: `(user_id, name)`
- [x] Create `category_rules` table:
  - `id UUID PK`, `user_id UUID NOT NULL`, `keyword VARCHAR(255) NOT NULL`
  - `category_id UUID NOT NULL REFERENCES categories(id)`
  - `subcategory_id UUID REFERENCES categories(id)`
  - `account_id UUID` — optional scope
  - `priority INT NOT NULL DEFAULT 0`
  - `created_at`, `updated_at`
- [x] Add index on `user_id` in all tables

**Acceptance Criteria:**
- [x] All three tables created with correct constraints
- [x] Self-referential FK on `categories.parent_id` created
- [x] Migration applies cleanly

**Automated Tests:**
- [x] `CategoriesSchemaMigrationTest` — asserts tables, FKs, and indexes exist

---

### Phase 1.4 — V3: Transaction Schema

**File:** `V3__create_transactions.sql`

**Implementation Tasks:**

- [x] Create `installment_series` table:
  - `id UUID PK`, `user_id UUID NOT NULL`
  - `total_amount NUMERIC(19,2) NOT NULL`
  - `total_installments INT NOT NULL`
  - `description VARCHAR(255) NOT NULL`
  - `category_id UUID REFERENCES categories(id)`
  - `account_id UUID NOT NULL REFERENCES accounts(id)`
  - `original_date DATE NOT NULL`
  - `settled BOOLEAN NOT NULL DEFAULT false`
  - `settled_at TIMESTAMP WITH TIME ZONE`
  - `created_at`, `updated_at`
- [x] Create `recurrence_rules` table:
  - `id UUID PK`, `user_id UUID NOT NULL`
  - `account_id UUID NOT NULL REFERENCES accounts(id)`
  - `type VARCHAR(20) NOT NULL`
  - `amount NUMERIC(19,2) NOT NULL`
  - `description VARCHAR(255) NOT NULL`
  - `category_id UUID REFERENCES categories(id)`
  - `frequency VARCHAR(20) NOT NULL` — `DAILY`, `WEEKLY`, `BIWEEKLY`, `MONTHLY`, `YEARLY`
  - `start_date DATE NOT NULL`
  - `end_date DATE`
  - `next_occurrence_date DATE`
  - `status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'`
  - `paused_at TIMESTAMP WITH TIME ZONE`
  - `resume_at TIMESTAMP WITH TIME ZONE`
  - `deleted_at TIMESTAMP WITH TIME ZONE`
  - `created_at`, `updated_at`
- [x] Create `transactions` table:
  - `id UUID PK DEFAULT gen_random_uuid()`
  - `user_id UUID NOT NULL`
  - `account_id UUID NOT NULL REFERENCES accounts(id)`
  - `type VARCHAR(30) NOT NULL` — `INCOME`, `EXPENSE`, `TRANSFER`, `REFUND`, `MANUAL_ADJUSTMENT`
  - `amount NUMERIC(19,2) NOT NULL`
  - `description VARCHAR(255) NOT NULL`
  - `notes TEXT`
  - `competence_date DATE NOT NULL`
  - `payment_date DATE`
  - `status VARCHAR(20) NOT NULL DEFAULT 'PAID'` — `PAID`, `PENDING`, `OVERDUE`, `CANCELLED`
  - `category_id UUID REFERENCES categories(id)`
  - `subcategory_id UUID REFERENCES categories(id)`
  - `location VARCHAR(255)`
  - `transfer_group_id UUID`
  - `installment_series_id UUID REFERENCES installment_series(id)`
  - `installment_number INT`
  - `total_installments INT`
  - `detached BOOLEAN NOT NULL DEFAULT false`
  - `early_settlement BOOLEAN NOT NULL DEFAULT false`
  - `recurrence_rule_id UUID REFERENCES recurrence_rules(id)`
  - `cancelled_at TIMESTAMP WITH TIME ZONE`
  - `created_at`, `updated_at`
- [x] Create `transaction_tags` table: `transaction_id UUID`, `tag_id UUID`, PK `(transaction_id, tag_id)`
- [x] Create `attachments` table:
  - `id UUID PK`, `user_id UUID NOT NULL`, `transaction_id UUID NOT NULL REFERENCES transactions(id)`
  - `filename VARCHAR(255) NOT NULL`, `mime_type VARCHAR(100) NOT NULL`
  - `file_size_bytes BIGINT NOT NULL`, `storage_key VARCHAR(512) NOT NULL`
  - `uploaded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()`
- [x] Add indexes:
  - `transactions(user_id)`, `transactions(account_id)`
  - `transactions(competence_date)`, `transactions(payment_date)`
  - `transactions(status)`, `transactions(installment_series_id)`
  - `transactions(recurrence_rule_id)`, `transactions(transfer_group_id)`
  - `recurrence_rules(next_occurrence_date)`
  - `attachments(transaction_id)`, `attachments(user_id)`

**Acceptance Criteria:**
- [x] All tables created with correct `NUMERIC(19,2)` amount columns
- [x] All FK constraints correct; no circular references
- [x] All indexes as specified

**Automated Tests:**
- [x] `TransactionSchemaMigrationTest` — asserts tables, FKs, and indexes

---

### Phase 1.5 — V4: Credit Card Schema

**File:** `V4__create_credit_cards.sql`

**Implementation Tasks:**

- [x] Create `credit_cards` table:
  - `id UUID PK`, `user_id UUID NOT NULL`
  - `name VARCHAR(100) NOT NULL`
  - `brand VARCHAR(30) NOT NULL` — `VISA`, `MASTERCARD`, `ELO`, `AMEX`, `HIPERCARD`, `OTHER`
  - `issuer VARCHAR(100)`
  - `credit_limit NUMERIC(19,2) NOT NULL`
  - `closing_day INT NOT NULL` — 1–28
  - `due_day INT NOT NULL` — 1–28
  - `shared_limit_group_id UUID`
  - `archived_at TIMESTAMP WITH TIME ZONE`
  - `created_at`, `updated_at`
  - Unique index: `(user_id, name)` WHERE `deleted_at IS NULL`
- [x] Create `invoices` table:
  - `id UUID PK`, `credit_card_id UUID NOT NULL REFERENCES credit_cards(id)`
  - `user_id UUID NOT NULL`
  - `reference_month CHAR(7) NOT NULL` — YYYY-MM
  - `closing_date DATE NOT NULL`
  - `due_date DATE NOT NULL`
  - `total_amount NUMERIC(19,2) NOT NULL DEFAULT 0`
  - `paid_amount NUMERIC(19,2) NOT NULL DEFAULT 0`
  - `status VARCHAR(20) NOT NULL DEFAULT 'OPEN'` — `OPEN`, `CLOSED`, `PAID`, `PARTIAL`, `OVERDUE`
  - `created_at`, `updated_at`
  - Unique index: `(credit_card_id, reference_month)`
- [x] Create `invoice_items` table:
  - `id UUID PK`, `invoice_id UUID NOT NULL REFERENCES invoices(id)`
  - `transaction_id UUID REFERENCES transactions(id)`
  - `amount NUMERIC(19,2) NOT NULL`
  - `description VARCHAR(255) NOT NULL`
  - `type VARCHAR(30) NOT NULL` — `CHARGE`, `REVOLVING`, `FUTURE_INSTALLMENT`
  - `item_date DATE NOT NULL`
  - `created_at`
- [x] Add indexes: `invoices(user_id)`, `invoices(due_date)`, `invoice_items(invoice_id)`

**Acceptance Criteria:**
- [x] All three tables with correct constraints and indexes
- [x] `closing_day` and `due_day` check constraints: `BETWEEN 1 AND 28`
- [x] Migration applies cleanly

**Automated Tests:**
- [x] `CreditCardSchemaMigrationTest` — asserts tables and constraints

---

### Phase 1.6 — V5: Default Category Seed Data

**File:** `V5__seed_default_categories.sql`

**Implementation Tasks:**

- [x] Insert default root categories (system-level, `user_id = NULL`, `is_default = true`):
  - Expenses: `Housing`, `Food`, `Transport`, `Health`, `Education`, `Entertainment`, `Clothing`, `Personal Care`, `Subscriptions`, `Travel`, `Taxes & Fees`, `Other Expenses`
  - Income: `Salary`, `Freelance`, `Investments`, `Gifts`, `Other Income`
- [x] Insert default subcategories under each root (e.g., Housing → Rent, Condominium, Electricity, Water, Internet)
- [x] Use `INSERT ... ON CONFLICT DO NOTHING` for idempotency

**Acceptance Criteria:**
- [x] All default categories present in `categories` table on fresh install
- [x] Re-running migration produces no duplicate key errors
- [x] All entries have `is_default = true`, `user_id = NULL`

**Automated Tests:**
- [x] `DefaultCategorySeedTest` — asserts minimum expected root categories exist

---

