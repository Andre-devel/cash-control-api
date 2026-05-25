# Database Schema — Cash Control API v1

**Stack:** Java 25 · Spring Boot 4.0.6 · Spring Security 7 · PostgreSQL 18 · Flyway · JPA/Hibernate  
**Format:** [DBML](https://dbml.dbdiagram.io/) — paste into [dbdiagram.io](https://dbdiagram.io/) to render  
**Design principles:**
- UUID primary keys (`gen_random_uuid()`) on all entities
- `NUMERIC(19,2)` for all monetary values — no `float` or `double`
- `timestamptz` for all timestamps (UTC-aware); `date` for calendar-only fields
- No database ENUM types — lookup tables for all categorical values
- All financial entities carry `user_id` FK — cross-user access is architecturally impossible
- Soft-delete on `accounts`, `categories`, `credit_cards`, `recurrence_rules` via `deleted_at`
- `CANCELLED` transactions are never hard-deleted — `cancelled_at` is set instead
- Append-only semantics respected: no destructive mutations on paid transactions
- Schema managed exclusively via Flyway versioned migrations

---

## Schema (DBML)

```dbml
// ============================================================
// Database Schema — Cash Control API v1
// ============================================================
// Stack   : Java 25 · Spring Boot 4.0.6 · Spring Security 7
//           PostgreSQL 18 · Flyway · JPA/Hibernate
// ============================================================
//
// Sections:
//   1. Lookup Tables        — categorical values (no ENUMs)
//   2. Accounts & Wallets   — financial accounts per user
//   3. Transactions         — all financial movements on accounts
//   4. Installment Series   — installment payment commitments
//   5. Recurring Rules      — scheduled transaction generation
//   6. Categories           — hierarchical transaction classification
//   7. Tags                 — free-form cross-category labeling
//   8. Credit Cards         — card configuration and shared limits
//   9. Invoices             — billing cycles and credit card charges
//  10. Attachments          — receipt and proof-of-payment files
// ============================================================

// ============================================================
// SECTION 1 — LOOKUP TABLES
// All categorical values are data-driven lookup rows, never
// database ENUMs or bare string columns.
// Standard shape: id · name · slug · description · is_active
//                 created_at · updated_at
// Seeded by Flyway baseline migration.
// ============================================================

Table account_types {
  id          uuid        [pk, default: `gen_random_uuid()`]
  name        varchar(50) [not null, unique]
  slug        varchar(50) [not null, unique]
  description text
  is_active   boolean     [not null, default: true]
  created_at  timestamptz [not null, default: `now()`]
  updated_at  timestamptz [not null, default: `now()`]

  indexes {
    slug [unique, name: "uidx_account_types_slug"]
  }

  Note: "Seed values: CHECKING, SAVINGS, CASH, VIRTUAL_WALLET, INTERNATIONAL, JOINT, INVESTMENT. INVESTMENT accounts are included in net worth but excluded from liquid balance aggregations."
}

Table transaction_types {
  id          uuid        [pk, default: `gen_random_uuid()`]
  name        varchar(50) [not null, unique]
  slug        varchar(50) [not null, unique]
  description text
  is_active   boolean     [not null, default: true]
  created_at  timestamptz [not null, default: `now()`]
  updated_at  timestamptz [not null, default: `now()`]

  indexes {
    slug [unique, name: "uidx_transaction_types_slug"]
  }

  Note: "Seed values: INCOME, EXPENSE, TRANSFER, REFUND, MANUAL_ADJUSTMENT. TRANSFER creates two linked transactions (debit + credit) via transfer_group_id. MANUAL_ADJUSTMENT is excluded from cash flow reports."
}

Table transaction_statuses {
  id          uuid        [pk, default: `gen_random_uuid()`]
  name        varchar(50) [not null, unique]
  slug        varchar(50) [not null, unique]
  description text
  is_active   boolean     [not null, default: true]
  created_at  timestamptz [not null, default: `now()`]
  updated_at  timestamptz [not null, default: `now()`]

  indexes {
    slug [unique, name: "uidx_transaction_statuses_slug"]
  }

  Note: "Seed values: PAID, PENDING, OVERDUE, CANCELLED. Valid transitions: PENDING→PAID, PENDING→OVERDUE (auto), PENDING→CANCELLED, OVERDUE→PAID, OVERDUE→CANCELLED. CANCELLED transactions are excluded from balance calculations but preserved for audit."
}

Table recurrence_frequencies {
  id          uuid        [pk, default: `gen_random_uuid()`]
  name        varchar(50) [not null, unique]
  slug        varchar(50) [not null, unique]
  description text
  is_active   boolean     [not null, default: true]
  created_at  timestamptz [not null, default: `now()`]
  updated_at  timestamptz [not null, default: `now()`]

  indexes {
    slug [unique, name: "uidx_recurrence_frequencies_slug"]
  }

  Note: "Seed values: DAILY, WEEKLY, BIWEEKLY, MONTHLY, YEARLY."
}

Table recurrence_rule_statuses {
  id          uuid        [pk, default: `gen_random_uuid()`]
  name        varchar(50) [not null, unique]
  slug        varchar(50) [not null, unique]
  description text
  is_active   boolean     [not null, default: true]
  created_at  timestamptz [not null, default: `now()`]
  updated_at  timestamptz [not null, default: `now()`]

  indexes {
    slug [unique, name: "uidx_recurrence_rule_statuses_slug"]
  }

  Note: "Seed values: ACTIVE (generating instances), PAUSED (suspended, no generation), ENDED (past end_date or manually closed), DELETED (soft-deleted, no future instances)."
}

Table card_brands {
  id          uuid        [pk, default: `gen_random_uuid()`]
  name        varchar(50) [not null, unique]
  slug        varchar(50) [not null, unique]
  description text
  is_active   boolean     [not null, default: true]
  created_at  timestamptz [not null, default: `now()`]
  updated_at  timestamptz [not null, default: `now()`]

  indexes {
    slug [unique, name: "uidx_card_brands_slug"]
  }

  Note: "Seed values: VISA, MASTERCARD, ELO, AMEX, HIPERCARD, OTHER."
}

Table invoice_statuses {
  id          uuid        [pk, default: `gen_random_uuid()`]
  name        varchar(50) [not null, unique]
  slug        varchar(50) [not null, unique]
  description text
  is_active   boolean     [not null, default: true]
  created_at  timestamptz [not null, default: `now()`]
  updated_at  timestamptz [not null, default: `now()`]

  indexes {
    slug [unique, name: "uidx_invoice_statuses_slug"]
  }

  Note: "Seed values: OPEN (current cycle, still accepting charges), CLOSED (cycle ended, awaiting payment), PAID (full payment recorded), PARTIAL (partial payment made, remainder carried as revolving), OVERDUE (due date passed without full payment)."
}

// ============================================================
// SECTION 2 — ACCOUNTS & WALLETS
// ============================================================

Table accounts {
  id            uuid         [pk, default: `gen_random_uuid()`]
  user_id       uuid         [not null, ref: > users.id, note: "Owning user. All queries must filter by user_id — cross-user access is rejected at the service layer."]
  type_id       uuid         [not null, ref: > account_types.id]
  name          varchar(100) [not null, note: "Unique per user among non-deleted accounts. Enforced via partial unique index on (user_id, name) WHERE deleted_at IS NULL."]
  currency_code varchar(3)   [not null, default: "BRL", note: "ISO 4217 code. Immutable after account creation to preserve transaction history consistency."]
  description   text
  sort_order    int          [not null, default: 0, note: "User-defined display ordering within account list."]
  archived_at   timestamptz  [note: "Set on archive action. Archived accounts are excluded from balance aggregations and cannot receive new transactions (HTTP 422)."]
  deleted_at    timestamptz  [note: "Soft-delete. Deletion only permitted when no transactions exist beyond the seed initial balance record. Non-null = logically deleted."]
  created_at    timestamptz  [not null, default: `now()`]
  updated_at    timestamptz  [not null, default: `now()`]

  indexes {
    user_id [name: "idx_accounts_user"]
    type_id [name: "idx_accounts_type"]
    (user_id, archived_at) [name: "idx_accounts_user_archived", note: "Active account list: WHERE archived_at IS NULL AND deleted_at IS NULL"]
    (user_id, deleted_at) [name: "idx_accounts_user_deleted"]
  }

  Note: "Each account has a seeded MANUAL_ADJUSTMENT transaction created at registration to represent the initial balance (US-1.1). Currency is immutable after creation. Archived accounts retain full transaction history but are excluded from dashboard aggregations. Deletion is guarded: only empty accounts (no transactions beyond the seed) may be hard-deleted."
}

// ============================================================
// SECTION 3 — TRANSACTIONS
// All financial movements on accounts (income, expense,
// transfer, refund, manual adjustment).
// Credit card charges live in invoice_items (Section 9).
// ============================================================

Table transactions {
  id                    uuid          [pk, default: `gen_random_uuid()`]
  user_id               uuid          [not null, ref: > users.id]
  account_id            uuid          [not null, ref: > accounts.id]
  type_id               uuid          [not null, ref: > transaction_types.id]
  status_id             uuid          [not null, ref: > transaction_statuses.id]
  amount                numeric(19,2) [not null, note: "Always positive. Direction is encoded by type_id: INCOME/REFUND add to balance; EXPENSE/TRANSFER-debit subtract; MANUAL_ADJUSTMENT may be negative if stored as signed."]
  description           varchar(255)  [not null]
  notes                 text
  competence_date       date          [not null, note: "The date the financial event occurred (accrual date)."]
  payment_date          date          [note: "The date the payment was actually settled. Defaults to competence_date when status=PAID and no explicit date is provided. NULL for PENDING transactions."]
  cancelled_at          timestamptz   [note: "Set when the transaction is cancelled. CANCELLED transactions are never hard-deleted and do not affect balances."]

  // Installment-specific fields — NULL for non-installment transactions
  installment_series_id uuid          [ref: > installment_series.id, note: "NULL for non-installment transactions. FK to the series master record."]
  installment_number    int           [note: "1-based position within the installment series. NULL for non-installment transactions."]
  total_installments    int           [note: "Total number of installments in the series at creation time. NULL for non-installment transactions."]
  is_detached           boolean       [not null, default: false, note: "True when this installment was individually edited and detached from series-wide operations (US-3.3)."]
  is_early_settlement   boolean       [not null, default: false, note: "True when this is the consolidated settlement transaction created by early payoff (US-3.4)."]

  // Recurrence — NULL for manually created transactions
  recurrence_rule_id    uuid          [ref: > recurrence_rules.id, note: "NULL for manually created transactions. Set when this transaction was generated by a recurrence_rule."]

  // Category classification — both nullable
  category_id           uuid          [ref: > categories.id, note: "Primary category. NULL if uncategorized."]
  subcategory_id        uuid          [ref: > categories.id, note: "Must be a child of category_id when provided."]

  // Transfer linking — NULL for non-transfer transactions
  transfer_group_id     uuid          [note: "Shared UUID between the two legs of a transfer (debit + credit). Both legs share this value; neither may be deleted without the other."]

  location              varchar(255)  [note: "Optional geolocation or address. Collected only when user explicitly provides it."]

  created_at            timestamptz   [not null, default: `now()`]
  updated_at            timestamptz   [not null, default: `now()`]

  indexes {
    user_id [name: "idx_transactions_user"]
    account_id [name: "idx_transactions_account"]
    (user_id, competence_date) [name: "idx_transactions_user_competence", note: "Primary history sort: reverse-chrono list"]
    (user_id, payment_date) [name: "idx_transactions_user_payment", note: "Cash-date reports and overdue detection"]
    (user_id, status_id) [name: "idx_transactions_user_status", note: "Filter by PENDING/OVERDUE for upcoming bills widget"]
    (user_id, type_id) [name: "idx_transactions_user_type"]
    (account_id, status_id) [name: "idx_transactions_account_status", note: "Balance computation: PAID transactions per account"]
    installment_series_id [name: "idx_transactions_installment_series"]
    recurrence_rule_id [name: "idx_transactions_recurrence"]
    category_id [name: "idx_transactions_category"]
    transfer_group_id [name: "idx_transactions_transfer_group", note: "Transfer pair lookup — both legs share this UUID"]
    (user_id, payment_date, status_id) [name: "idx_transactions_overdue_scan", note: "Overdue detection: WHERE payment_date < today AND status = PENDING"]
  }

  Note: "balance = SUM of signed amounts of all PAID transactions on an account, where INCOME/REFUND are positive and EXPENSE/TRANSFER-debit are negative. PENDING, OVERDUE, and CANCELLED transactions do not affect the settled balance. MANUAL_ADJUSTMENT transactions are excluded from cash flow (income/expense) aggregations but included in balance. Transfer pairs share transfer_group_id and must be deleted as a unit."
}

// ============================================================
// SECTION 4 — INSTALLMENT SERIES
// Master record for installment payment commitments.
// Individual installments are child transactions (account-based)
// or child invoice_items (card-based).
// ============================================================

Table installment_series {
  id                 uuid          [pk, default: `gen_random_uuid()`]
  user_id            uuid          [not null, ref: > users.id]
  account_id         uuid          [ref: > accounts.id, note: "Set for account-based installments; NULL for card-based. Exactly one of account_id or credit_card_id must be non-null."]
  credit_card_id     uuid          [ref: > credit_cards.id, note: "Set for card-based installments; NULL for account-based. Exactly one of account_id or credit_card_id must be non-null."]
  type_id            uuid          [not null, ref: > transaction_types.id, note: "Typically EXPENSE."]
  description        varchar(255)  [not null]
  total_amount       numeric(19,2) [not null, note: "Original committed total. Per-installment amount = total / count; remainder assigned to first installment."]
  total_installments int           [not null]
  first_payment_date date          [not null]
  category_id        uuid          [ref: > categories.id]
  subcategory_id     uuid          [ref: > categories.id]
  is_settled         boolean       [not null, default: false, note: "True after early settlement (US-3.4). Remaining installments are cancelled and a single settlement transaction is created."]
  settled_at         timestamptz
  created_at         timestamptz   [not null, default: `now()`]
  updated_at         timestamptz   [not null, default: `now()`]

  indexes {
    user_id [name: "idx_installment_series_user"]
    account_id [name: "idx_installment_series_account"]
    credit_card_id [name: "idx_installment_series_card"]
    (user_id, is_settled) [name: "idx_installment_series_active"]
  }

  Note: "Exactly one of account_id or credit_card_id must be set; enforced at the application layer. Child records are transactions (for account_id) or invoice_items (for credit_card_id). is_settled=true indicates the series was closed by early settlement; settled_at is set and remaining installments are CANCELLED."
}

// ============================================================
// SECTION 5 — RECURRING TRANSACTIONS
// Schedule definitions for automatic transaction generation.
// Applies to account-based transactions only.
// ============================================================

Table recurrence_rules {
  id                   uuid          [pk, default: `gen_random_uuid()`]
  user_id              uuid          [not null, ref: > users.id]
  account_id           uuid          [not null, ref: > accounts.id]
  type_id              uuid          [not null, ref: > transaction_types.id]
  status_id            uuid          [not null, ref: > recurrence_rule_statuses.id]
  frequency_id         uuid          [not null, ref: > recurrence_frequencies.id]
  amount               numeric(19,2) [not null]
  description          varchar(255)  [not null]
  category_id          uuid          [ref: > categories.id]
  subcategory_id       uuid          [ref: > categories.id]
  start_date           date          [not null]
  end_date             date          [note: "NULL = open-ended recurrence. Non-null = bounded by this date. Status transitions to ENDED when next_occurrence_date > end_date."]
  next_occurrence_date date          [note: "Next date an instance should be generated. NULL when status=ENDED or DELETED. Updated after each instance generation."]
  paused_at            timestamptz   [note: "Set when status transitions to PAUSED. No instances generated while paused."]
  resume_at            timestamptz   [note: "Optional scheduled resume date set at pause time. When resume_at <= today, status transitions back to ACTIVE."]
  deleted_at           timestamptz   [note: "Soft-delete. FUTURE_ONLY strategy: only cancels remaining PENDING instances. ALL strategy: cancels all PENDING instances."]
  created_at           timestamptz   [not null, default: `now()`]
  updated_at           timestamptz   [not null, default: `now()`]

  indexes {
    user_id [name: "idx_recurrence_rules_user"]
    account_id [name: "idx_recurrence_rules_account"]
    (status_id, next_occurrence_date) [name: "idx_recurrence_rules_scheduler", note: "Scheduler query: WHERE status=ACTIVE AND next_occurrence_date <= today"]
    (user_id, status_id) [name: "idx_recurrence_rules_user_status"]
    (user_id, deleted_at) [name: "idx_recurrence_rules_user_deleted"]
  }

  Note: "Instances are generated ahead of time or lazily at query time — implementation choice. Pausing cancels existing PENDING instances for the pause window. Editing updates all future PENDING instances and re-generates them. Individual instances may be detached from the rule (recurrence_rule_id preserved on the transaction but changes to that transaction do not affect siblings)."
}

// ============================================================
// SECTION 6 — CATEGORIES
// Two-level hierarchy: root category → subcategory.
// System defaults (user_id IS NULL) cannot be deleted.
// ============================================================

Table categories {
  id          uuid         [pk, default: `gen_random_uuid()`]
  user_id     uuid         [ref: > users.id, note: "NULL for system default categories. Non-null = user-created. System defaults are shared across all users; user categories are strictly scoped."]
  parent_id   uuid         [ref: > categories.id, note: "NULL for root categories. Non-null for subcategories. Max depth = 2: subcategories cannot have children."]
  name        varchar(100) [not null]
  color       varchar(7)   [note: "Hex color code (e.g. #FF5733). Used for UI rendering."]
  icon        varchar(50)  [note: "Icon identifier from a predefined set."]
  sort_order  int          [not null, default: 0]
  is_default  boolean      [not null, default: false, note: "System-provided. Cannot be deleted; may only be hidden (is_hidden=true)."]
  is_hidden   boolean      [not null, default: false, note: "Excluded from category pickers. Existing transactions retain their categorization."]
  is_archived boolean      [not null, default: false, note: "User-defined categories only. Archived = excluded from pickers and cannot receive new transactions. Archiving a parent also archives all children."]
  archived_at timestamptz
  created_at  timestamptz  [not null, default: `now()`]
  updated_at  timestamptz  [not null, default: `now()`]

  indexes {
    user_id [name: "idx_categories_user"]
    parent_id [name: "idx_categories_parent"]
    (user_id, parent_id, name) [unique, name: "uidx_categories_name_per_scope", note: "Name unique within parent scope per user. Partial uniqueness enforced at application layer for IS NULL parent_id cases."]
    is_default [name: "idx_categories_default"]
    (user_id, is_hidden) [name: "idx_categories_user_hidden"]
    (user_id, is_archived) [name: "idx_categories_user_archived"]
  }

  Note: "user_id IS NULL = system default. user_id IS NOT NULL = user-created. Name uniqueness is scoped to (user_id, parent_id): a root category and a subcategory under a different root may share the same name. Auto-suggestion (US-5.6) and category rules (US-5.7) reference category_id and subcategory_id from this table."
}

Table category_rules {
  id              uuid         [pk, default: `gen_random_uuid()`]
  user_id         uuid         [not null, ref: > users.id]
  pattern         varchar(255) [not null, note: "Keyword or text pattern matched against transaction description at creation time."]
  category_id     uuid         [not null, ref: > categories.id]
  subcategory_id  uuid         [ref: > categories.id, note: "Optional subcategory override. Must be a child of category_id."]
  account_id      uuid         [ref: > accounts.id, note: "NULL = rule applies to all accounts. Non-null = scoped to a specific account."]
  priority        int          [not null, default: 0, note: "Lower value = higher priority. When multiple rules match, the highest priority rule wins."]
  is_active       boolean      [not null, default: true]
  created_at      timestamptz  [not null, default: `now()`]
  updated_at      timestamptz  [not null, default: `now()`]

  indexes {
    user_id [name: "idx_category_rules_user"]
    (user_id, is_active, priority) [name: "idx_category_rules_active_priority", note: "Rule evaluation: all active rules for user ordered by priority"]
    category_id [name: "idx_category_rules_category"]
    account_id [name: "idx_category_rules_account"]
  }

  Note: "Rules are evaluated at transaction creation time. If auto-assigned, the user may override the category on the individual transaction without deleting the rule. Rules are applied in ascending priority order; first match wins."
}

// ============================================================
// SECTION 7 — TAGS
// Free-form labeling for cross-category transaction grouping.
// ============================================================

Table tags {
  id         uuid        [pk, default: `gen_random_uuid()`]
  user_id    uuid        [not null, ref: > users.id]
  name       varchar(50) [not null]
  color      varchar(7)  [note: "Hex color for tag chip rendering. Optional."]
  created_at timestamptz [not null, default: `now()`]
  updated_at timestamptz [not null, default: `now()`]

  indexes {
    (user_id, name) [unique, name: "uidx_tags_user_name"]
    user_id [name: "idx_tags_user"]
  }

  Note: "User-scoped free-form labels. Tags may be assigned to any number of transactions. Tag name is unique per user."
}

Table transaction_tags {
  id             uuid        [pk, default: `gen_random_uuid()`]
  transaction_id uuid        [not null, ref: > transactions.id]
  tag_id         uuid        [not null, ref: > tags.id]
  created_at     timestamptz [not null, default: `now()`]

  indexes {
    (transaction_id, tag_id) [unique, name: "uidx_transaction_tags"]
    transaction_id [name: "idx_transaction_tags_transaction"]
    tag_id [name: "idx_transaction_tags_tag"]
  }

  Note: "Many-to-many join table. No soft-delete: tag removal drops the row. Tags can be filtered on transaction list queries (US-2.6)."
}

Table invoice_item_tags {
  id              uuid        [pk, default: `gen_random_uuid()`]
  invoice_item_id uuid        [not null, ref: > invoice_items.id]
  tag_id          uuid        [not null, ref: > tags.id]
  created_at      timestamptz [not null, default: `now()`]

  indexes {
    (invoice_item_id, tag_id) [unique, name: "uidx_invoice_item_tags"]
    invoice_item_id [name: "idx_invoice_item_tags_item"]
    tag_id [name: "idx_invoice_item_tags_tag"]
  }

  Note: "Many-to-many join table for tags on credit card charges (US-6.2). Mirrors transaction_tags for the invoice_items domain."
}

// ============================================================
// SECTION 8 — CREDIT CARDS & SHARED LIMITS
// ============================================================

Table shared_limit_groups {
  id          uuid          [pk, default: `gen_random_uuid()`]
  user_id     uuid          [not null, ref: > users.id]
  name        varchar(100)  [not null]
  total_limit numeric(19,2) [not null, note: "The combined credit limit shared across all cards in this group."]
  created_at  timestamptz   [not null, default: `now()`]
  updated_at  timestamptz   [not null, default: `now()`]

  indexes {
    user_id [name: "idx_shared_limit_groups_user"]
    (user_id, name) [unique, name: "uidx_shared_limit_groups_user_name"]
  }

  Note: "Optional grouping for cards that share a combined credit limit. US-6.5: available limit calculation must consider the group-level used amount, not just per-card usage."
}

Table credit_cards {
  id                    uuid          [pk, default: `gen_random_uuid()`]
  user_id               uuid          [not null, ref: > users.id]
  brand_id              uuid          [not null, ref: > card_brands.id]
  name                  varchar(100)  [not null, note: "Unique per user among non-deleted cards."]
  issuer                varchar(100)  [note: "Issuing bank or institution name."]
  credit_limit          numeric(19,2) [not null]
  closing_day           int           [not null, note: "Day of month (1–28) when the billing cycle closes. Charges after closing_day are assigned to the next invoice."]
  due_day               int           [not null, note: "Day of month (1–28) when payment is due."]
  shared_limit_group_id uuid          [ref: > shared_limit_groups.id, note: "NULL if this card has an independent limit."]
  archived_at           timestamptz   [note: "Archived cards cannot receive new charges (HTTP 422)."]
  deleted_at            timestamptz   [note: "Soft-delete. Invoices and charge history are preserved."]
  created_at            timestamptz   [not null, default: `now()`]
  updated_at            timestamptz   [not null, default: `now()`]

  indexes {
    user_id [name: "idx_credit_cards_user"]
    brand_id [name: "idx_credit_cards_brand"]
    shared_limit_group_id [name: "idx_credit_cards_shared_group"]
    (user_id, deleted_at) [name: "idx_credit_cards_user_deleted"]
    (user_id, archived_at) [name: "idx_credit_cards_user_archived"]
  }

  Note: "On card creation, the first open invoice is created automatically for the current billing cycle (US-6.1). closing_day determines which billing cycle a charge falls into: a charge on competence_date after closing_day goes to the next invoice. used_limit is computed at query time as the sum of non-cancelled invoice_item amounts in the current open invoice plus future-attributed installment charges."
}

// ============================================================
// SECTION 9 — INVOICES & CREDIT CARD CHARGES
// ============================================================

Table invoices {
  id              uuid          [pk, default: `gen_random_uuid()`]
  user_id         uuid          [not null, ref: > users.id]
  credit_card_id  uuid          [not null, ref: > credit_cards.id]
  status_id       uuid          [not null, ref: > invoice_statuses.id]
  reference_month varchar(7)    [not null, note: "Billing cycle identifier in YYYY-MM format. Unique per credit card."]
  closing_date    date          [not null]
  due_date        date          [not null]
  total_amount    numeric(19,2) [not null, default: 0, note: "Sum of all non-cancelled invoice_item amounts. Updated as charges are added or cancelled."]
  paid_amount     numeric(19,2) [not null, default: 0, note: "Amount paid. For PARTIAL invoices, remainder = total_amount - paid_amount carried as REVOLVING charge on next invoice."]
  created_at      timestamptz   [not null, default: `now()`]
  updated_at      timestamptz   [not null, default: `now()`]

  indexes {
    user_id [name: "idx_invoices_user"]
    credit_card_id [name: "idx_invoices_card"]
    (credit_card_id, reference_month) [unique, name: "uidx_invoices_card_month"]
    (user_id, due_date) [name: "idx_invoices_user_due", note: "Upcoming invoices widget: due_date <= today + window"]
    (user_id, status_id) [name: "idx_invoices_user_status"]
    due_date [name: "idx_invoices_due_date", note: "Overdue detection: WHERE due_date < today AND status NOT IN (PAID)"]
  }

  Note: "One invoice per billing cycle per card. OPEN: current cycle. CLOSED: awaiting payment after closing_date. PAID: full payment. PARTIAL: partial payment; unpaid remainder is carried as a new REVOLVING invoice_item on the next invoice. OVERDUE: due_date passed without payment."
}

Table invoice_items {
  id                    uuid          [pk, default: `gen_random_uuid()`]
  user_id               uuid          [not null, ref: > users.id]
  invoice_id            uuid          [not null, ref: > invoices.id]
  description           varchar(255)  [not null]
  amount                numeric(19,2) [not null, note: "Always positive. Represents a charge on the card."]
  competence_date       date          [not null, note: "Date the charge occurred. Determines which invoice this item belongs to based on the card's closing_day."]
  category_id           uuid          [ref: > categories.id]
  subcategory_id        uuid          [ref: > categories.id]
  notes                 text

  // Installment-specific fields — NULL for standalone charges
  installment_series_id uuid          [ref: > installment_series.id, note: "NULL for one-off charges. Set for card-based installment charges."]
  installment_number    int           [note: "1-based position within the series. NULL for non-installment items."]
  total_installments    int           [note: "Total installments in the series. NULL for non-installment items."]
  is_detached           boolean       [not null, default: false, note: "True when individually edited and detached from series-wide operations."]

  // Revolving credit
  is_revolving          boolean       [not null, default: false, note: "True when this charge was carried from a previous PARTIAL invoice as unpaid remainder."]

  cancelled_at          timestamptz   [note: "Set when the charge is cancelled. Cancelled items are excluded from invoice total_amount."]
  created_at            timestamptz   [not null, default: `now()`]
  updated_at            timestamptz   [not null, default: `now()`]

  indexes {
    user_id [name: "idx_invoice_items_user"]
    invoice_id [name: "idx_invoice_items_invoice"]
    (invoice_id, competence_date) [name: "idx_invoice_items_invoice_date"]
    installment_series_id [name: "idx_invoice_items_series"]
    category_id [name: "idx_invoice_items_category"]
    (user_id, cancelled_at) [name: "idx_invoice_items_user_cancelled"]
  }

  Note: "Represents a credit card charge within a billing cycle. Charge-to-invoice assignment is based on the card's closing_day relative to competence_date. Card-based installment charges are distributed across future invoices: the first installment goes to the current open invoice, subsequent ones to future invoices as they are created. is_revolving=true identifies the auto-generated remainder charge when a PARTIAL invoice payment is recorded."
}

// ============================================================
// SECTION 10 — ATTACHMENTS
// File metadata for receipts and proof-of-payment.
// Only linked to account transactions (not invoice_items).
// ============================================================

Table attachments {
  id                 uuid         [pk, default: `gen_random_uuid()`]
  user_id            uuid         [not null, ref: > users.id]
  transaction_id     uuid         [not null, ref: > transactions.id]
  original_filename  varchar(255) [not null, note: "Original filename as provided by the user. Never used to construct storage paths."]
  mime_type          varchar(100) [not null, note: "Validated MIME type: application/pdf, image/png, image/jpeg. Enforced at upload boundary."]
  file_size_bytes    bigint       [not null]
  storage_key        varchar(500) [not null, unique, note: "Internal storage reference (e.g. object storage key). Never exposed in API responses; raw file paths are access-controlled."]
  uploaded_at        timestamptz  [not null, default: `now()`]
  deleted_at         timestamptz  [note: "Soft-delete. Deletion removes access but preserves metadata for audit."]
  created_at         timestamptz  [not null, default: `now()`]

  indexes {
    user_id [name: "idx_attachments_user"]
    transaction_id [name: "idx_attachments_transaction"]
    (transaction_id, deleted_at) [name: "idx_attachments_transaction_active", note: "Active attachments per transaction: WHERE deleted_at IS NULL"]
    (user_id, deleted_at) [name: "idx_attachments_user_deleted"]
    storage_key [unique, name: "uidx_attachments_storage_key"]
  }

  Note: "Multiple attachments per transaction are permitted up to a configurable limit. Supported MIME types are validated at the API boundary. storage_key is an internal reference to the object storage system; API responses return only metadata (UUID, filename, MIME type, size, timestamp) — never the raw storage URL."
}
```

---

## Table Summary

| Table | Section | Purpose |
|---|---|---|
| `account_types` | Lookup | Account categories: CHECKING, SAVINGS, CASH, VIRTUAL_WALLET, INTERNATIONAL, JOINT, INVESTMENT |
| `transaction_types` | Lookup | Financial movement types: INCOME, EXPENSE, TRANSFER, REFUND, MANUAL_ADJUSTMENT |
| `transaction_statuses` | Lookup | Payment lifecycle states: PAID, PENDING, OVERDUE, CANCELLED |
| `recurrence_frequencies` | Lookup | Recurrence schedules: DAILY, WEEKLY, BIWEEKLY, MONTHLY, YEARLY |
| `recurrence_rule_statuses` | Lookup | Recurrence rule states: ACTIVE, PAUSED, ENDED, DELETED |
| `card_brands` | Lookup | Credit card networks: VISA, MASTERCARD, ELO, AMEX, HIPERCARD, OTHER |
| `invoice_statuses` | Lookup | Invoice billing states: OPEN, CLOSED, PAID, PARTIAL, OVERDUE |
| `accounts` | Accounts | Financial accounts and wallets per user; supports archiving and soft-delete |
| `transactions` | Transactions | All financial movements on accounts; includes installment and recurrence linkage |
| `installment_series` | Installments | Master record for installment commitments on accounts or credit cards |
| `recurrence_rules` | Recurrence | Recurring transaction schedule definitions for account-based transactions |
| `categories` | Categories | Two-level category hierarchy; supports system defaults and user-created categories |
| `category_rules` | Categories | User-defined auto-categorization rules matched at transaction creation |
| `tags` | Tags | Free-form tag definitions per user |
| `transaction_tags` | Tags | Many-to-many: transaction ↔ tag |
| `invoice_item_tags` | Tags | Many-to-many: invoice_item ↔ tag (credit card charge tagging) |
| `shared_limit_groups` | Credit Cards | Optional shared credit limit groups spanning multiple cards |
| `credit_cards` | Credit Cards | Card configuration: brand, limit, billing cycle, due date |
| `invoices` | Invoices | Monthly billing cycles per credit card with status and payment tracking |
| `invoice_items` | Invoices | Individual credit card charges per invoice; supports installment and revolving credit |
| `attachments` | Attachments | Receipt and proof-of-payment file metadata linked to transactions |

---

## Key Design Decisions

### Transactions vs Invoice Items

Account-based financial activity (`accounts` → `transactions`) and credit card charges (`credit_cards` → `invoices` → `invoice_items`) are modelled as separate entity trees. This separation reflects their different lifecycle semantics: account transactions affect the account balance directly, while credit card charges affect the invoice total and only impact a bank account when the invoice is paid (which creates a separate `EXPENSE` transaction on the source account).

### Installment Series Across Both Domains

`installment_series` can reference either `account_id` or `credit_card_id` — exactly one must be set. Account-based series generate `transactions` as children; card-based series generate `invoice_items` distributed across future invoices. This single master record models both domains without schema duplication.

### Balance Semantics

Account balance is computed at query time by summing signed amounts of all `PAID` transactions on an account:
- `INCOME`, `REFUND`, positive `MANUAL_ADJUSTMENT` → add to balance
- `EXPENSE`, `TRANSFER` debit leg → subtract from balance

`PENDING`, `OVERDUE`, and `CANCELLED` transactions are excluded from the settled balance. A `pendingBalance` projection may include PENDING transactions for cash flow planning.

### Monetary Precision

All financial amounts use `NUMERIC(19,2)` in PostgreSQL, `BigDecimal` in Java, and string serialization in API responses. No `float`, `double`, or `real` types appear anywhere in the monetary data path.

### Soft-Delete Strategy

| Entity | Soft-Delete Field | Hard-Delete Allowed? |
|---|---|---|
| `accounts` | `deleted_at` | Only when no transactions beyond seed record |
| `categories` | via `is_archived` + `archived_at` | Never for system defaults; archive is the maximum action |
| `credit_cards` | `deleted_at` | No — invoice history is preserved |
| `recurrence_rules` | `deleted_at` | No — PENDING instances are cancelled, rule is soft-deleted |
| `transactions` | `cancelled_at` (CANCELLED status) | Never — CANCELLED records are preserved for audit |
| `invoice_items` | `cancelled_at` | Never — cancelled items are preserved for audit |
| `attachments` | `deleted_at` | Soft-delete removes access; metadata is retained |

### Credit Card Invoice Assignment

A charge's invoice is determined at creation time: if `competence_date` falls on or before the card's `closing_day` in the current month, the charge goes to the current open invoice; otherwise it goes to the next invoice. Card-based installment series distribute future installment items to upcoming invoices as those invoices are created.

### No Database ENUMs

Every categorical column uses a lookup table FK. New account types, transaction types, or card brands are added via Flyway data migrations — zero DDL changes required.