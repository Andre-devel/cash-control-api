# Cash Control — Technical & Architectural Specification

## Document Purpose

This document defines the technical and functional architecture of a production-ready
personal finance management module built on Spring Boot 4 and Java 25. It serves as
the core domain specification for the Cash Control API, covering all financial entities,
workflows, and behavioral contracts.

This document covers architecture, design decisions, domain model, and functional
behavior. It does not prescribe implementation steps, database migrations, code, or
delivery phases.

---

## 1. System Overview

### 1.1 Purpose

A self-contained personal finance management backend designed to track accounts,
transactions, categories, credit cards, and spending patterns for individual users
in a multi-account, multi-currency-aware environment.

### 1.2 Core Capabilities

| Capability                  | Description                                                       |
|-----------------------------|-------------------------------------------------------------------|
| Account & wallet management | Multiple account types with balance tracking and archiving        |
| Transaction lifecycle       | Income, expense, transfer, refund, manual adjustment              |
| Installment tracking        | Recurring and installment payment series with partial editing     |
| Category management         | Hierarchical categories with color, icon, and auto-suggestion     |
| Credit card billing         | Card limits, billing cycles, invoice tracking, partial payment    |
| Dashboard & reporting       | Aggregated views, cash flow, charts, and configurable widgets     |
| Tag system                  | Free-form tagging for cross-category transaction grouping         |
| Attachment support          | Receipt and proof-of-payment file association                     |

### 1.3 Architectural Posture

- **User-scoped isolation**: all financial data is strictly scoped to the authenticated user.
- **Audit-ready**: all mutations produce structured event records.
- **Privacy-by-design**: LGPD-aligned minimal data collection.
- **Stateless backend**: integrates with the authentication module for JWT-based identity.
- **Cloud-native ready**: Docker/Kubernetes-friendly, environment-based configuration.

---

## 2. Technology Stack

| Layer                  | Technology                                      |
|------------------------|-------------------------------------------------|
| Language               | Java 25                                         |
| Framework              | Spring Boot 4.0.6                               |
| Security               | Spring Security 7 (JWT via auth module)         |
| Persistence            | Spring Data JPA / Hibernate, PostgreSQL 18      |
| Schema migrations      | Flyway                                          |
| Build tool             | Gradle                                          |
| Validation             | Bean Validation / Jakarta Validation            |
| Utilities              | Lombok                                          |
| Infrastructure         | Docker-ready architecture                       |

---

## 3. Account & Wallet Architecture

### 3.1 Account Types

The system supports multiple account types under a single user, each with independent
balance tracking and lifecycle management.

| Type                  | Description                                                         |
|-----------------------|---------------------------------------------------------------------|
| `CHECKING`            | Standard bank checking account                                      |
| `SAVINGS`             | Savings account with optional interest tracking                     |
| `CASH`                | Physical cash wallet                                                |
| `VIRTUAL_WALLET`      | Digital wallet (e.g., PIX keys, digital payment apps)              |
| `INTERNATIONAL`       | Foreign currency account; supports currency code                    |
| `JOINT`               | Shared account between two or more users or participants            |
| `INVESTMENT`          | Investment account; balance treated as asset in net worth           |

### 3.2 Account Lifecycle

- Accounts are created with an **initial balance** that seeds the account's opening state.
- Accounts may be **archived** (soft-disabled) without deleting historical transaction data.
- Archived accounts are excluded from active balance aggregations but remain queryable.
- **Manual balance adjustment** is supported as a special transaction type that reconciles
  the actual balance with the system-calculated balance without distorting cash flow reports.

### 3.3 Balance Semantics

- The system maintains a computed balance derived from transaction history.
- The initial balance is treated as a seed transaction at account creation time.
- Manual adjustments are recorded as `MANUAL_ADJUSTMENT` transactions with an
  explicit before/after balance for auditability.
- Transfers between accounts produce two linked transactions (debit + credit) that
  net to zero at the total portfolio level.

### 3.4 Multiple Accounts

- A user may hold an unlimited number of accounts across all types.
- Dashboard aggregations (total balance, net worth) span all non-archived accounts.
- Investment accounts are included in net worth but excluded from liquid cash calculations.

---

## 4. Transaction Architecture

### 4.1 Transaction Types

| Type               | Description                                                          |
|--------------------|----------------------------------------------------------------------|
| `INCOME`           | Money received (salary, freelance, sale, etc.)                       |
| `EXPENSE`          | Money spent                                                          |
| `TRANSFER`         | Movement between two accounts owned by the user                      |
| `REFUND`           | Reversal of a previous expense; increases balance                    |
| `MANUAL_ADJUSTMENT`| Balance reconciliation; does not affect cash flow reports            |

### 4.2 Transaction Data Model

Each transaction carries the following data fields:

| Field               | Description                                                         |
|---------------------|---------------------------------------------------------------------|
| `amount`            | Monetary value (positive; direction encoded by type)                |
| `description`       | Short human-readable label                                          |
| `notes`             | Free-form long-form observations                                    |
| `competenceDate`    | The date the financial event occurred (accrual date)                |
| `paymentDate`       | The date the payment was actually settled (cash date)               |
| `attachments`       | File references (receipts, invoices, proofs of payment)             |
| `location`          | Optional geolocation or address associated with the transaction     |
| `tags`              | Free-form labels for cross-category grouping                        |
| `category`          | Primary category classification                                     |
| `subcategory`       | Secondary classification within the parent category                 |
| `status`            | Payment status: `PAID`, `PENDING`, `OVERDUE`, `CANCELLED`          |

### 4.3 Transaction Status Lifecycle

```
PENDING → PAID
PENDING → OVERDUE  (automatic, when paymentDate passes without settlement)
PENDING → CANCELLED
OVERDUE → PAID
OVERDUE → CANCELLED
```

- Status transitions are validated; invalid transitions are rejected with a domain error.
- `OVERDUE` status may be updated by a scheduled background job or on-demand at query time.
- `CANCELLED` transactions are preserved for audit purposes; they do not affect balances.

### 4.4 Installment Transactions

Installments represent a single financial commitment split across multiple payment dates.

**Installment model:**
- A root `installment_series` record holds the master terms (total amount, total installments,
  description, category, account, original date).
- Each installment is an individual `transaction` record linked to the series, carrying its
  own `installmentNumber`, `totalInstallments`, `amount` (per installment), and `paymentDate`.

**Installment operations:**
- **Edit individual installment**: modifies only the selected installment record.
- **Edit entire series**: propagates changes to all future (unpaid) installments in the series.
- **Early settlement (quitação antecipada)**: marks all remaining installments as paid and
  optionally adjusts amounts for early payoff discount.
- **Early advance (antecipação)**: moves the payment date of one or more future installments
  forward, optionally with a discount amount.

### 4.5 Recurring Transactions

Recurring transactions auto-generate future transaction instances on a defined schedule.

| Frequency    | Description                                     |
|--------------|-------------------------------------------------|
| `DAILY`      | Every day                                       |
| `WEEKLY`     | Every 7 days                                    |
| `BIWEEKLY`   | Every 14 days                                   |
| `MONTHLY`    | Same day each month                             |
| `YEARLY`     | Same date each year                             |

**Recurrence model:**
- A `recurrence_rule` record holds the schedule, frequency, start date, and optional end date.
- Transactions are generated ahead of time or lazily on request, depending on implementation choice.
- **End conditions**: open-ended (no end date) or bounded by a specific end date.
- **Pause**: a recurrence may be paused; no new instances are generated during the pause window.
- Editing a single instance detaches it from the series (generates a divergent copy).
- Editing the entire series from a point forward re-generates all future instances.

---

## 5. Category Architecture

### 5.1 Category Structure

Categories form a two-level hierarchy:

```
Category (parent)
└── Subcategory (child)
```

- A category without a parent is a root category.
- A subcategory always has exactly one parent category.
- Transactions reference either a category or a subcategory; if a subcategory is selected,
  the parent category is implicitly associated.

### 5.2 Category Properties

| Property          | Description                                                       |
|-------------------|-------------------------------------------------------------------|
| `name`            | Human-readable label (user-customizable)                          |
| `color`           | Hex color code for UI rendering                                   |
| `icon`            | Icon identifier (from a predefined set or custom upload)          |
| `order`           | User-defined display sort order                                   |
| `isDefault`       | System-provided category (cannot be deleted, only hidden)         |
| `isUserDefined`   | Created by the user                                               |
| `isHidden`        | Excluded from category pickers; not deleted                       |
| `isArchived`      | Soft-disabled; no new transactions may reference it               |

### 5.3 Default vs User Categories

- The system ships with a set of **default categories** covering common expense and income types.
- Default categories cannot be deleted. They may be hidden or renamed.
- Users may create unlimited custom categories and subcategories.
- Custom categories may be archived when no longer needed.

### 5.4 Category Intelligence

| Feature                | Description                                                       |
|------------------------|-------------------------------------------------------------------|
| Auto-suggestion        | Suggests category based on transaction description using history  |
| Automatic rules        | User-defined rules mapping keywords or merchants to categories    |
| Category learning      | System improves suggestions based on confirmed categorizations    |

---

## 6. Credit Card Architecture

### 6.1 Card Model

Each credit card is a distinct entity linked to a user, with its own billing configuration.

| Field              | Description                                                       |
|--------------------|-------------------------------------------------------------------|
| `name`             | User-defined card name                                            |
| `brand`            | Card network: `VISA`, `MASTERCARD`, `ELO`, `AMEX`, etc.          |
| `issuer`           | Issuing bank or institution                                       |
| `limit`            | Total credit limit                                                |
| `sharedLimit`      | Whether this card shares its limit with another card (group)      |
| `closingDay`       | Day of month when the billing cycle closes                        |
| `dueDay`           | Day of month when payment is due                                  |

### 6.2 Invoice Architecture

Each billing cycle produces one invoice.

| Field                | Description                                                     |
|----------------------|-----------------------------------------------------------------|
| `referenceMonth`     | The month and year this invoice covers                          |
| `closingDate`        | Date the invoice closed (no more charges added)                 |
| `dueDate`            | Payment due date                                                |
| `totalAmount`        | Sum of all charges in this cycle                                |
| `status`             | `OPEN`, `CLOSED`, `PAID`, `PARTIAL`, `OVERDUE`                  |
| `paidAmount`         | Amount paid when status is `PARTIAL`                            |

**Invoice states:**
- `OPEN`: current cycle, still accepting new charges.
- `CLOSED`: cycle ended; awaiting payment.
- `PAID`: full payment recorded.
- `PARTIAL`: partial payment made; remainder enters revolving credit.
- `OVERDUE`: due date passed without full payment.

**Partial payment and revolving credit:**
- A partial payment records the paid amount and carries the remainder as a new charge
  on the next invoice, tagged as `REVOLVING`.
- The system tracks installments that belong to future invoices as `FUTURE_INSTALLMENTS`.

### 6.3 Card Visualizations

| View                      | Description                                                    |
|---------------------------|----------------------------------------------------------------|
| Limit usage               | Used vs. available credit limit                                |
| Available limit           | Remaining credit after current open invoice charges           |
| Spending by card          | Total spending per card per period                             |
| Spending by category      | Category breakdown of charges on a given card                  |
| Multiple cards            | Consolidated view across all cards                             |

---

## 7. Dashboard & Reporting Architecture

### 7.1 Overview Metrics

The dashboard provides at-a-glance financial health indicators:

| Metric               | Description                                                      |
|----------------------|------------------------------------------------------------------|
| Total balance        | Sum of all non-archived, non-investment account balances         |
| Total net worth      | Total balance + investment account balances                      |
| Monthly income       | Total `INCOME` transactions in the current calendar month        |
| Monthly expenses     | Total `EXPENSE` transactions in the current calendar month       |
| Monthly savings      | Monthly income minus monthly expenses                            |
| Cash flow            | Net movement (income − expense) over a configurable time window  |

### 7.2 Charts

| Chart Type              | Description                                                    |
|-------------------------|----------------------------------------------------------------|
| Category pie chart      | Expense distribution by category for a given period           |
| Timeline line chart     | Balance or cash flow trend over time                          |
| Monthly bar chart       | Income vs. expense bars per month                             |
| Monthly comparison      | Side-by-side comparison of two or more months                 |
| Net worth evolution     | Net worth progression over time                               |

### 7.3 Dashboard Widgets

Widgets are configurable, composable units that populate the main dashboard view.

| Widget                | Description                                                       |
|-----------------------|-------------------------------------------------------------------|
| Upcoming bills        | Pending/overdue transactions due within a configurable window      |
| Upcoming invoices     | Credit card invoices due soon                                     |
| Goals                 | Progress toward user-defined financial goals                      |
| Subscriptions         | Recurring expenses tagged as subscriptions                        |
| Recent transactions   | Latest N transactions across all accounts                         |
| Largest expenses      | Top spending transactions in the current period                   |

---

## 8. Persistence Architecture

### 8.1 Primary Entities

| Entity                 | Purpose                                                            |
|------------------------|--------------------------------------------------------------------|
| `accounts`             | User accounts and wallets with type, balance, and lifecycle state  |
| `transactions`         | All financial movements with full data model                       |
| `installment_series`   | Master record for installment payment commitments                  |
| `recurrence_rules`     | Schedule definition for recurring transaction generation           |
| `categories`           | Category and subcategory definitions                               |
| `category_rules`       | Auto-categorization rules defined by the user                      |
| `tags`                 | Tag definitions per user                                           |
| `transaction_tags`     | Many-to-many: transaction ↔ tag                                    |
| `credit_cards`         | Credit card configuration per user                                 |
| `invoices`             | Monthly billing cycle records per credit card                      |
| `invoice_items`        | Individual charges belonging to an invoice                         |
| `attachments`          | File metadata linked to transactions                               |

### 8.2 Entity Design Constraints

- **UUID** primary keys on all entities.
- `created_at` and `updated_at` timestamps, auto-managed by JPA/Hibernate.
- All entities are strictly scoped to a `user_id` foreign key; cross-user access is
  architecturally impossible at the query level.
- Soft-delete on `accounts` and `categories` via `deleted_at` nullable timestamp.
- `CANCELLED` transactions are never hard-deleted; `cancelled_at` timestamp is set.
- Appropriate unique constraints: account name per user, category name per parent per user,
  card name per user.
- Indexes on: `user_id` (all entities), `transactions.competence_date`,
  `transactions.payment_date`, `transactions.status`, `invoices.due_date`.

### 8.3 Schema Management

- All schema changes managed exclusively via Flyway versioned migrations.
- No schema changes via Hibernate `ddl-auto` in production environments.
- Flyway is configured to run automatically on application startup in controlled
  environments only.

---

## 9. Authorization & Data Scoping

### 9.1 User Isolation

Every domain entity carries a `user_id` foreign key referencing the authenticated principal.
All repository queries include a `WHERE user_id = :currentUserId` predicate enforced at
the service layer — never rely on the client to scope data.

### 9.2 Authorization Enforcement

- Spring Security method-level security (`@PreAuthorize`) gates all service operations.
- JWT identity from the authentication module is the sole source of `currentUserId`.
- No cross-user data access is permitted; attempts return `403 Forbidden`.

---

## 10. API Response Conventions

- Consistent envelope structure for all API responses.
- Errors return a standardized error body with: error code, generic message, and correlation ID.
- HTTP status codes follow REST conventions strictly.
- Authorization failures: `403 Forbidden`.
- Validation errors: `400 Bad Request` with field-level details.
- Resource not found: `404 Not Found` (only when the resource exists for the authenticated user).
- All timestamps are returned in ISO 8601 format, UTC timezone.
- Monetary values are returned as `BigDecimal` strings to avoid floating-point imprecision.

---

## 11. Privacy & LGPD Requirements

### 11.1 Data Minimization

- Only data necessary for personal finance management is collected.
- No sensitive government identifiers (CPF, RG) are required or stored.
- Location data is optional and user-initiated; never collected passively.

### 11.2 Sensitive Data Handling

- Attachment files are stored with access-controlled URLs; direct URL exposure is avoided.
- Financial data is never included in logs beyond correlation identifiers.
- Account numbers and card numbers (if stored) are masked in all log output.

### 11.3 LGPD Future Readiness

| Future Capability               | Architecture Requirement                            |
|---------------------------------|-----------------------------------------------------|
| Account deletion / right to erasure | Soft-delete + anonymization pipeline on all entities |
| Data portability                | Exportable transaction and account history (CSV/JSON)|
| Consent management              | Consent flags for optional data features (location) |
| Log retention controls          | Configurable TTL on audit and transaction tables     |

---

## 12. Architectural Patterns & Design Principles

### 12.1 Layered Architecture

```
Controller (API) Layer
→ Receives HTTP requests, validates DTOs, delegates to service
→ Returns standardized API responses

Service Layer
→ Encapsulates all business and financial logic
→ Enforces user scoping and authorization
→ Throws domain-specific exceptions

Repository Layer
→ Spring Data JPA repositories
→ No business logic; data access only

Domain Layer
→ JPA entities and value objects
→ Domain exceptions and enums

Security Layer
→ JWT authentication via auth module integration
→ Method-level authorization
```

### 12.2 Design Principles

- **Single Responsibility**: each class has one clear reason to change.
- **Dependency Inversion**: services depend on interfaces, not concrete implementations.
- **DTO pattern**: request/response objects are strictly separated from JPA entities;
  entities never leak outside the service layer.
- **Centralized exception handling**: a global `@ControllerAdvice` maps domain exceptions
  to appropriate HTTP responses uniformly.
- **Environment-based configuration**: `application.yml` uses environment variable binding;
  no secrets in default profiles.

### 12.3 Extensibility Points

| Extension Point                | Future Use Case                                      |
|--------------------------------|------------------------------------------------------|
| Multi-currency support         | Exchange rate integration and currency conversion    |
| Goal engine                    | Savings goal tracking with progress calculations     |
| Subscription detection         | Auto-detecting recurring expenses as subscriptions   |
| Notification port              | Due-date alerts, overspending warnings               |
| Report export                  | PDF/CSV statement generation                         |
| Open Banking integration       | Automatic transaction import from bank feeds         |
| Multi-tenancy / family sharing | Shared budget views across accounts                  |

---

## 13. Non-Functional Requirements

| Requirement          | Target                                                              |
|----------------------|---------------------------------------------------------------------|
| **Availability**     | Stateless design enables horizontal scaling with no affinity        |
| **Security**         | All data strictly user-scoped; no cross-user leakage possible       |
| **Observability**    | Structured JSON logs; correlation IDs; domain event stream          |
| **Testability**      | Pure service layer, mockable ports, no static coupling              |
| **Deployability**    | Docker-ready; externalized configuration; health endpoints          |
| **Compliance**       | LGPD-aligned data minimization and audit trail                      |
| **Precision**        | Monetary values use `BigDecimal`; no floating-point arithmetic       |
| **Portability**      | No vendor-specific APIs; standard Spring ecosystem only             |