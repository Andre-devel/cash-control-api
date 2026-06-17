# Implementation Roadmap — Payment Method Feature (Backend)

**Stack:** Java 25 · Spring Boot 4.0.6 · Spring Security 7 · PostgreSQL 18 · Flyway · JPA/Hibernate · Gradle  
**Architecture:** Stateless JWT · User-scoped data isolation · LGPD-aligned · Docker-ready  
**Feature:** Payment method selection on transactions and installment series; conditional credit card linkage  
**Generated:** 2026-06-06  
**Status legend:** `[x]` = implemented · `[ ]` = pending

---

## Codebase Inspection Summary

| Area                                        | Status |
|---------------------------------------------|--------|
| `payment_methods` lookup table              | `[x]` V15 migration |
| `transactions.payment_method_id` column     | `[x]` V16 migration |
| `transactions.credit_card_id` column        | `[x]` V16 migration |
| `installment_series.payment_method_id` col  | `[x]` V17 migration |
| `PaymentMethod` entity / enum               | `[x]` Implemented |
| `Transaction` entity updated                | `[x]` Implemented |
| `InstallmentSeries` entity updated          | `[x]` Implemented |
| `CreateTransactionRequest` updated          | `[x]` Implemented |
| `EditTransactionRequest` updated            | `[x]` Implemented |
| `CreateInstallmentRequest` updated          | `[x]` Implemented |
| `EditSeriesRequest` updated                 | `[x]` Implemented |
| `TransactionFilterRequest` updated          | `[x]` Implemented |
| `TransactionDetailResponse` updated         | `[x]` Implemented |
| `TransactionSummaryResponse` updated        | `[x]` Implemented |
| `PaymentMethodController`                   | `[x]` Implemented |
| `TransactionService` conditional validation | `[x]` Implemented |
| `InstallmentService` updated                | `[x]` Implemented |
| Unit tests                                  | `[x]` Implemented |
| Integration tests                           | `[x]` Implemented |

**Overall status:** Complete.

---

## Implementation Strategy

Phases are ordered by dependency: database schema → domain entities → DTOs → service
layer validation → controller/response changes → tests. The `payment_methods` lookup
table must exist before any entity or migration that references it. Conditional
validation (creditCardId required ↔ CREDIT_CARD) is enforced at the service layer so
error messages are domain-meaningful, not constraint-violation noise.

---

## Phase 1 — Database Migrations

**Objective:** Add `payment_methods` lookup table and extend `transactions` and
`installment_series` with the new columns, backfilling existing rows to `OTHER`.

**Dependencies:** Existing V13 migration must be the last applied migration.

**Complexity:** Low

### Phase 1.1 — Create payment_methods Table (V14)

**Implementation Tasks:**

- [x] Create `V15__create_payment_methods_table.sql` in `src/main/resources/db/migration` (V14 already taken; used V15)
- [x] Define columns: `id` (uuid PK), `name` (varchar 50 unique), `slug` (varchar 50 unique),
      `description` (text), `is_active` (boolean, true), `created_at`, `updated_at`
- [x] Add unique index on `slug`
- [x] Insert the seven seed rows: `CASH`, `PIX`, `DEBIT_CARD`, `CREDIT_CARD`,
      `BANK_TRANSFER`, `BOLETO`, `OTHER`
- [x] Capture and expose the `OTHER` row UUID as a variable for use in V16 and V17

### Phase 1.2 — Extend transactions Table (V15)

**Implementation Tasks:**

- [x] Create `V16__add_payment_method_to_transactions.sql`
- [x] Add `payment_method_id uuid NOT NULL REFERENCES payment_methods(id)` (backfill from OTHER, then drop default)
- [x] Add `credit_card_id uuid REFERENCES credit_cards(id)` (nullable)
- [x] Add index on `payment_method_id`: `idx_transactions_payment_method`
- [x] Add index on `credit_card_id`: `idx_transactions_credit_card`

### Phase 1.3 — Extend installment_series Table (V16)

**Implementation Tasks:**

- [x] Create `V17__add_payment_method_to_installment_series.sql`
- [x] Add `payment_method_id uuid NOT NULL REFERENCES payment_methods(id)` (backfill from OTHER, then drop default)
- [x] Add index on `payment_method_id`: `idx_installment_series_payment_method`

---

## Phase 2 — Domain Entities

**Objective:** Add the `PaymentMethod` JPA entity and update `Transaction` and
`InstallmentSeries` entities with the new relationship fields.

**Dependencies:** Phase 1 migrations applied.

**Complexity:** Low

### Phase 2.1 — PaymentMethod Entity

**Implementation Tasks:**

- [x] Create `PaymentMethod.java` in `com.cashcontrol.api.domain.entity`
- [x] Fields: `id` (UUID), `name` (String), `slug` (PaymentMethodSlug enum), `isActive` (boolean),
      `createdAt` (Instant), `updatedAt` (Instant)
- [x] Annotate with `@Entity`, `@Table(name = "payment_methods")`
- [x] Create `PaymentMethodSlug` enum: `CASH`, `PIX`, `DEBIT_CARD`, `CREDIT_CARD`,
      `BANK_TRANSFER`, `BOLETO`, `OTHER`

### Phase 2.2 — Update Transaction Entity

**Implementation Tasks:**

- [x] Add `@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "payment_method_id") PaymentMethod paymentMethod` field
- [x] Add `@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "credit_card_id") CreditCard creditCard` nullable field

### Phase 2.3 — Update InstallmentSeries Entity

**Implementation Tasks:**

- [x] Add `@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "payment_method_id") PaymentMethod paymentMethod` field

---

## Phase 3 — DTOs

**Objective:** Add `paymentMethod` and `creditCardId` to request and response DTOs.

**Dependencies:** Phase 2.

**Complexity:** Low

### Phase 3.1 — Request DTOs

**Implementation Tasks:**

- [x] `CreateTransactionRequest`: add `PaymentMethodSlug paymentMethod` (nullable), `UUID creditCardId` (nullable)
- [x] `EditTransactionRequest`: add same two fields as nullable patches
- [x] `CreateInstallmentRequest`: add `PaymentMethodSlug paymentMethod` (nullable), `UUID creditCardId` (nullable)
- [x] `EditSeriesRequest`: add `PaymentMethodSlug paymentMethod` (nullable), `UUID creditCardId` (nullable)
- [x] `TransactionFilterRequest`: add `PaymentMethodSlug paymentMethod` (nullable filter)

### Phase 3.2 — Response DTOs

**Implementation Tasks:**

- [x] Create `PaymentMethodResponse` record: `id`, `slug`, `name`
- [x] Create `CreditCardRefResponse` record: `id`, `name`, `brand`
- [x] `TransactionSummaryResponse`: add `PaymentMethodResponse paymentMethod`
- [x] `TransactionDetailResponse`: add `PaymentMethodResponse paymentMethod`, `CreditCardRefResponse creditCard` (nullable)

---

## Phase 4 — Service Layer

**Objective:** Implement conditional validation and propagation logic for payment method
in both `TransactionService` and `InstallmentService`.

**Dependencies:** Phase 3.

**Complexity:** Medium

### Phase 4.1 — PaymentMethodRepository

**Implementation Tasks:**

- [x] Create `PaymentMethodRepository` extending `JpaRepository<PaymentMethod, UUID>`
- [x] Add `Optional<PaymentMethod> findBySlug(PaymentMethodSlug slug)`
- [x] Add `List<PaymentMethod> findAllByIsActiveTrue()`

### Phase 4.2 — TransactionService Validation

**Implementation Tasks:**

- [x] On create/edit: resolve `paymentMethod` entity from slug (default to `OTHER` if null)
- [x] If `paymentMethod = CREDIT_CARD` and `creditCardId` is null → `BusinessRuleException` → HTTP 422
- [x] If `paymentMethod ≠ CREDIT_CARD` and `creditCardId` is not null → `BusinessRuleException` → HTTP 422
- [x] If `creditCardId` provided: load `CreditCard`, validate it belongs to `userId` → HTTP 403; validate not archived → HTTP 422
- [x] Persist `paymentMethod` and `creditCard` on the `Transaction` entity
- [x] Include `paymentMethod` filter in list query when `paymentMethod` filter is set

### Phase 4.3 — InstallmentService Validation

**Implementation Tasks:**

- [x] Apply the same conditional validation logic as Phase 4.2 to `createInstallmentSeries`
- [x] Propagate `paymentMethod` and `creditCard` to each generated installment `Transaction`
- [x] On `editSeries`: propagate `paymentMethod` and `creditCard` changes to all future `PENDING`/`OVERDUE` installments

---

## Phase 5 — Controller & Lookup Endpoint

**Objective:** Expose the payment methods list and wire the filter into the transaction list endpoint.

**Dependencies:** Phase 4.

**Complexity:** Low

### Phase 5.1 — PaymentMethodController

**Implementation Tasks:**

- [x] Create `PaymentMethodController` with `GET /api/v1/payment-methods`
- [x] Returns all active `PaymentMethod` records as `List<PaymentMethodResponse>`
- [x] Requires `isAuthenticated()`
- [x] Orders by fixed display order (CASH, PIX, DEBIT_CARD, CREDIT_CARD, BANK_TRANSFER, BOLETO, OTHER)

### Phase 5.2 — TransactionController Filter

**Implementation Tasks:**

- [x] Add `paymentMethod` query parameter to `GET /api/v1/transactions`
- [x] Pass through to `TransactionFilterRequest` and service layer

---

## Phase 6 — Tests

**Objective:** Verify conditional validation, credit card ownership enforcement, and
propagation to installment transactions.

**Dependencies:** Phase 5.

**Complexity:** Medium

### Phase 6.1 — Unit Tests

**Implementation Tasks:**

- [x] `TransactionServiceTest`: test `CREDIT_CARD` without `creditCardId` → `BusinessRuleException`
- [x] `TransactionServiceTest`: test non-`CREDIT_CARD` with `creditCardId` → `BusinessRuleException`
- [x] `TransactionServiceTest`: test `creditCardId` belonging to wrong user → `ForbiddenAccessException`
- [x] `TransactionServiceTest`: test archived credit card → `BusinessRuleException`
- [x] `TransactionServiceTest`: test happy path with valid `CREDIT_CARD` and card reference in response

### Phase 6.2 — Integration Tests

**Implementation Tasks:**

- [x] `TransactionControllerIT`: create transaction with `CREDIT_CARD` → verify card linkage in response (HTTP 201)
- [x] `TransactionControllerIT`: create transaction with `CREDIT_CARD` missing `creditCardId` → HTTP 422
- [x] `TransactionControllerIT`: create transaction with `PIX` and `creditCardId` present → HTTP 422
- [x] `TransactionControllerIT`: filter by `paymentMethod=PIX` → only PIX transactions returned
- [x] `InstallmentControllerIT`: create series with `CREDIT_CARD` → all installments carry card reference
- [x] `PaymentMethodControllerIT`: `GET /api/v1/payment-methods` returns seven rows
- [x] `PaymentMethodSchemaMigrationTest`: V15/V16/V17 schema changes verified
