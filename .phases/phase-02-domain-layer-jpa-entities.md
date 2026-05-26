## Phase 2 — Domain Layer (JPA Entities)

**Objective:** Implement all JPA entities mapping to the Flyway-created schema. Entities must match the schema exactly; no schema changes via Hibernate auto-DDL.

**Dependencies:** Phase 1 complete.

**Complexity:** Medium

### Phase 2.1 — Account & Category Entities

**Implementation Tasks:**

- [x] Create `Account.java` entity: all fields from `accounts` table; `@Enumerated(EnumType.STRING)` for `type`; `@Column(nullable = false)` guards
- [x] Create `AccountType.java` enum: `CHECKING`, `SAVINGS`, `CASH`, `VIRTUAL_WALLET`, `INTERNATIONAL`, `JOINT`, `INVESTMENT`
- [x] Create `Category.java` entity: self-referential `@ManyToOne parent`; `@OneToMany subcategories`
- [x] Create `Tag.java` entity
- [x] Create `CategoryRule.java` entity

**Acceptance Criteria:**
- [x] `./gradlew test` with `ddl-auto: validate` passes (schema matches entities)
- [x] All FK relationships navigable via entity graph

**Automated Tests:**
- [x] `AccountEntityTest` — CRUD via `BaseRepositoryTest`
- [x] `CategoryEntityTest` — asserts parent-child navigation

---

### Phase 2.2 — Transaction & Related Entities

**Implementation Tasks:**

- [x] Create `TransactionType.java` enum: `INCOME`, `EXPENSE`, `TRANSFER`, `REFUND`, `MANUAL_ADJUSTMENT`
- [x] Create `TransactionStatus.java` enum: `PAID`, `PENDING`, `OVERDUE`, `CANCELLED`
- [x] Create `InstallmentSeries.java` entity
- [x] Create `RecurrenceRule.java` entity; `RecurrenceFrequency.java` enum: `DAILY`, `WEEKLY`, `BIWEEKLY`, `MONTHLY`, `YEARLY`
- [x] Create `Transaction.java` entity: all fields; `@ManyToMany tags`; `@ManyToOne` for account, category, subcategory, installmentSeries, recurrenceRule
- [x] Create `Attachment.java` entity: `@ManyToOne transaction`

**Acceptance Criteria:**
- [x] Schema validation passes with `ddl-auto: validate`
- [x] `Transaction` ↔ `Tag` many-to-many via `transaction_tags` join table

**Automated Tests:**
- [x] `TransactionEntityTest` — full lifecycle CRUD via repository

---

### Phase 2.3 — Credit Card Entities

**Implementation Tasks:**

- [x] Create `CardBrand.java` enum: `VISA`, `MASTERCARD`, `ELO`, `AMEX`, `HIPERCARD`, `OTHER`
- [x] Create `InvoiceStatus.java` enum: `OPEN`, `CLOSED`, `PAID`, `PARTIAL`, `OVERDUE`
- [x] Create `CreditCard.java` entity
- [x] Create `Invoice.java` entity: `@ManyToOne creditCard`; `@OneToMany items`
- [x] Create `InvoiceItem.java` entity: `@ManyToOne invoice`; `@ManyToOne transaction` (nullable)

**Acceptance Criteria:**
- [x] Schema validation passes
- [x] Invoice ↔ CreditCard navigation works

**Automated Tests:**
- [x] `CreditCardEntityTest` — asserts invoice creation and item linking

---

### Phase 2.4 — Domain Exceptions

**Implementation Tasks:**

- [x] Create `ResourceNotFoundException.java` — thrown when a resource is not found for the authenticated user
- [x] Create `BusinessRuleException.java` — thrown for 422 business rule violations (e.g., transfer to archived account)
- [x] Create `ConflictException.java` — thrown for 409 conflicts (e.g., duplicate account name)
- [x] Create `ForbiddenAccessException.java` — thrown on cross-user access attempts
- [x] Create `GlobalExceptionHandler.java` — `@ControllerAdvice` mapping all domain exceptions to correct HTTP status codes and standardized error body

**Acceptance Criteria:**
- [x] `ResourceNotFoundException` → 404
- [x] `BusinessRuleException` → 422
- [x] `ConflictException` → 409
- [x] `ForbiddenAccessException` → 403
- [x] Error body always contains: `errorCode`, `message`, `correlationId`; never stack traces

**Automated Tests:**
- [x] `GlobalExceptionHandlerTest` — `@WebMvcTest` asserting each exception maps to the correct HTTP status and body structure

---

