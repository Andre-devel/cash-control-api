# Payment Method — Backend Technical & Architectural Specification

## Document Purpose

This document defines the technical and functional architecture of the payment method
feature for the Cash Control API. It specifies how transactions and installment series
record the method by which a financial operation was settled, including the conditional
linkage to a credit card entity when the method is `CREDIT_CARD`.

This document covers architecture, design decisions, domain model, and functional
behavior. It does not prescribe implementation steps, database migrations, code, or
delivery phases.

---

## 1. Feature Overview

### 1.1 Purpose

Allow authenticated users to associate a payment method with every transaction and
installment series, enabling accurate cash flow classification, card-level spend
reporting, and filtering by payment channel.

### 1.2 Core Capabilities

| Capability                      | Description                                                               |
|---------------------------------|---------------------------------------------------------------------------|
| Payment method selection        | User selects how a transaction was or will be paid                        |
| Credit card linkage             | When method is `CREDIT_CARD`, a specific card entity is required          |
| Invoice assignment              | Credit card charges are automatically assigned to the correct billing cycle|
| Debit card tracking             | Debit card transactions debit the linked account immediately              |
| Installment series integration  | Installment series inherits payment method; `CREDIT_CARD` links a card    |
| Filtering & reporting           | Transactions filterable by payment method                                 |

### 1.3 Architectural Posture

- **Non-breaking addition**: `paymentMethod` defaults to `OTHER` for existing records —
  no historical data is invalidated.
- **Conditional constraint**: `creditCardId` is required if and only if
  `paymentMethod = CREDIT_CARD`.
- **Lookup-table driven**: payment method values are stored in a `payment_methods`
  lookup table — no database-level ENUMs.
- **User-scoped**: a credit card referenced in a transaction must belong to the
  authenticated user.

---

## 2. Payment Method Domain

### 2.1 Payment Method Values

| Slug              | Label (PT)              | Description                                                    |
|-------------------|-------------------------|----------------------------------------------------------------|
| `CASH`            | Dinheiro                | Physical cash payment                                          |
| `PIX`             | PIX                     | Instant Brazilian payment system                               |
| `DEBIT_CARD`      | Cartão de Débito        | Debit card; debits linked account immediately                  |
| `CREDIT_CARD`     | Cartão de Crédito       | Credit card; requires `creditCardId`; charge added to invoice  |
| `BANK_TRANSFER`   | Transferência Bancária  | TED, DOC, or wire transfer                                     |
| `BOLETO`          | Boleto Bancário         | Brazilian bank slip                                            |
| `OTHER`           | Outro                   | Catch-all for unlisted methods; default for legacy records     |

### 2.2 Credit Card Conditional Rule

When `paymentMethod = CREDIT_CARD`:
- `creditCardId` **must** be provided and must reference a credit card belonging to the
  authenticated user.
- The charge is assigned to the appropriate billing invoice determined by the card's
  `closingDay` and the transaction's `competenceDate`.
- The transaction `account` field remains required for debit tracking; for credit card
  transactions the account represents the payment source account (used when the invoice
  is paid).

When `paymentMethod ≠ CREDIT_CARD`:
- `creditCardId` **must** be null or absent.
- Providing a `creditCardId` for non-credit-card methods is rejected with HTTP 422.

### 2.3 Debit Card Behavior

A `DEBIT_CARD` transaction behaves identically to a `CASH` or `PIX` transaction from
a balance-impact perspective — the linked account balance is debited immediately when
the transaction status is `PAID`. The `paymentMethod` field is informational for
reporting; it does not alter balance semantics.

---

## 3. Transaction Architecture Changes

### 3.1 New Fields on `transactions`

| Field               | Type    | Nullable | Description                                                   |
|---------------------|---------|----------|---------------------------------------------------------------|
| `payment_method_id` | uuid FK | NO       | References `payment_methods.id`; defaults to `OTHER`         |
| `credit_card_id`    | uuid FK | YES      | References `credit_cards.id`; required only for `CREDIT_CARD`|

### 3.2 Validation Rules

- `paymentMethod` is always required on create (backend defaults to `OTHER` if absent
  to preserve backwards compatibility during migration).
- `creditCardId` is required when `paymentMethod = CREDIT_CARD`.
- `creditCardId` must be null when `paymentMethod ≠ CREDIT_CARD`.
- The referenced `creditCardId` must belong to the authenticated user — never
  accepted from an arbitrary UUID.
- Archived or deleted credit cards cannot be used as `creditCardId`.

### 3.3 Installment Series Integration

`installment_series` already carries a `credit_card_id` nullable FK. This feature
formalizes the `payment_method_id` field on `installment_series` as well, so that the
series-level payment method is propagated to all generated installment transactions.

- When an installment series is created with `paymentMethod = CREDIT_CARD`,
  `creditCardId` is required and each generated installment transaction receives both
  `payment_method_id` and `credit_card_id`.
- Editing the series `paymentMethod` propagates to all future (unpaid) installments.

---

## 4. API Changes

### 4.1 CreateTransactionRequest

New optional fields added:

```
paymentMethod  : PaymentMethodSlug   (optional; defaults to OTHER)
creditCardId   : UUID                (conditional; required if paymentMethod = CREDIT_CARD)
```

### 4.2 EditTransactionRequest

New editable fields:

```
paymentMethod  : PaymentMethodSlug   (optional patch)
creditCardId   : UUID                (conditional; required if paymentMethod = CREDIT_CARD)
```

Changing `paymentMethod` from `CREDIT_CARD` to another value nullifies `creditCardId`.
Changing to `CREDIT_CARD` requires a `creditCardId` to be supplied in the same request.

### 4.3 TransactionSummaryResponse / TransactionDetailResponse

New fields in responses:

```
paymentMethod  : { id, slug, name }
creditCard     : { id, name, brand } | null
```

### 4.4 TransactionFilterRequest

New filter parameter:

```
paymentMethod  : PaymentMethodSlug   (optional; exact match)
```

---

## 5. Persistence Architecture

### 5.1 New Table

- `payment_methods` — lookup table seeded by Flyway baseline migration with the seven
  canonical values. Never mutated at runtime.

### 5.2 Modified Tables

- `transactions` — two new columns: `payment_method_id` (FK, not null, default to
  `OTHER` row), `credit_card_id` (FK, nullable).
- `installment_series` — one new column: `payment_method_id` (FK, not null, default to
  `OTHER` row). The `credit_card_id` column already exists.

### 5.3 Indexes

- Index on `transactions.payment_method_id` for filtering by payment channel.

---

## 6. Authorization & Data Scoping

- `creditCardId` is validated against the authenticated user's credit cards at the
  service layer before any persistence operation.
- The payment method lookup table is global (no `user_id`) — all users share the same
  payment method slugs.
- No new RBAC permissions are introduced; existing `isAuthenticated()` gate on
  transaction endpoints is sufficient.

---

## 7. Non-Functional Requirements

| Requirement       | Target                                                                          |
|-------------------|---------------------------------------------------------------------------------|
| **Backwards compat** | Existing transactions without `paymentMethod` default to `OTHER` via migration |
| **Precision**     | No monetary changes; existing `BigDecimal` semantics unchanged                  |
| **Validation**    | Conditional `creditCardId` enforcement at both controller (Bean Validation) and service layers |
| **Testability**   | Unit tests cover conditional validation; integration tests cover full create-with-credit-card flow |