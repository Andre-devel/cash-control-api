# Implementation Roadmap — cash-control-api (v1)

**Stack:** Java 25 · Spring Boot 4.0.6 · Spring Security 7 · PostgreSQL 18 · Flyway · JPA/Hibernate · Gradle  
**Architecture:** Stateless JWT (via auth module) · User-scoped data isolation · LGPD-aligned · Docker-ready  
**Domain:** Personal finance — accounts, transactions, installments, recurring, categories, credit cards, dashboard  
**Generated:** 2026-05-25  
**Status legend:** `[x]` = implemented · `[ ]` = pending

---

## Codebase Inspection Summary

| Area | Status |
|---|---|
| Gradle build files | `[x]` Done — `build.gradle.kts`, `settings.gradle.kts` |
| Spring Boot application entry point | `[x]` Done — `AuthApplication.java` (package: `com.cashcontrol.api`) |
| Auth module source (`src/`) | `[x]` Done — full auth implementation (JWT, RBAC, users, audit) |
| Flyway migrations (auth) | `[x]` Done — V1–V8 (auth schema, seed data) |
| Flyway migrations (cash-control) | `[x]` Done — V9–V13 (accounts, categories, transactions, credit cards, seed) |
| Configuration files | `[x]` Done — `application.yml`, `application-test.yml` |
| Docker artifacts | `[ ]` Pending — `Dockerfile`, `docker-compose.yml`, `.dockerignore` |
| Test infrastructure | `[x]` Done — unit + integration test suites (Testcontainers) |
| CI/CD pipeline | `[ ]` Pending — `.github/workflows/ci.yml` |

**Status:** Phases 0–7 complete. Phase 8 (Category Management) is next.

---

## Implementation Strategy

Phases are ordered by dependency: infrastructure → database schema → domain entities → feature modules → cross-cutting concerns → testing → CI/CD. The auth module provides JWT-based identity; this module never manages credentials or authentication. Each phase produces a testable vertical slice. Security-critical foundations (user scoping, method-level authorization) are established before any business feature is implemented.

---

## Phase 0 — Project Bootstrap & Build Infrastructure

**Objective:** Produce a compilable, runnable Spring Boot application skeleton with all dependencies declared, configuration externalized, and the test harness bootstrapped.

**Dependencies:** None — this is the starting point.

**Complexity:** Low

### Phase 0.1 — Gradle Build Configuration

**Implementation Tasks:**

- [x] Create `settings.gradle.kts` — set `rootProject.name = "cash-control-api"`
- [x] Create `build.gradle.kts` with the following dependency blocks:
  - `org.springframework.boot` plugin version `4.0.6`
  - `io.spring.dependency-management` plugin
  - `java` plugin targeting Java 25 (toolchain `JavaLanguageVersion.of(25)`)
  - Spring Boot Starter Web
  - Spring Boot Starter Security
  - Spring Boot Starter Data JPA
  - Spring Boot Starter Validation
  - Spring Boot Starter Actuator
  - `org.flywaydb:flyway-core` + `flyway-database-postgresql`
  - `org.postgresql:postgresql`
  - Lombok + annotation processor
  - `org.springframework.boot:spring-boot-starter-test` (test scope)
  - `org.testcontainers:postgresql` (test scope)
  - `org.testcontainers:junit-jupiter` (test scope)
  - `org.springdoc:springdoc-openapi-starter-webmvc-ui` (v2.8.8)
  - `net.logstash.logback:logstash-logback-encoder` (v8.0, JSON logging)
- [x] Configure `compileJava.options.annotationProcessorPath` for Lombok
- [x] Configure `test { useJUnitPlatform() }`
- [x] Create `gradle/wrapper/gradle-wrapper.properties` targeting Gradle 9.5.1
- [x] Verify `./gradlew build` succeeds on an empty source set

**Acceptance Criteria:**
- [x] `./gradlew dependencies` resolves without conflicts
- [x] `./gradlew compileJava` succeeds
- [x] `./gradlew test` runs with zero tests and exits 0

**Automated Tests:**
- [x] Gradle build smoke test (CI step — not a JUnit test)

---

### Phase 0.2 — Application Entry Point & Package Structure

**Implementation Tasks:**

- [x] Create package root: `com.cashcontrol.api` (adapted from spec; auth module already established this)
- [x] Create `AuthApplication.java` with `@SpringBootApplication` (serves as project entry point)
- [x] Establish enforced package structure:
  ```
  com.cashcontrol.api
  ├── config/          — Spring configuration classes
  ├── controller/      — @RestController classes only
  ├── service/         — Business logic interfaces + implementations
  ├── repository/      — Spring Data JPA interfaces
  ├── domain/
  │   ├── entity/      — JPA @Entity classes
  │   └── exception/   — Domain-specific exceptions
  ├── dto/
  │   ├── request/     — Inbound API DTOs (@Valid targets)
  │   └── response/    — Outbound API DTOs
  ├── security/        — JWT filter, SecurityConfig
  └── util/            — Sanitization, correlation ID utilities
  ```
- [x] Create `src/main/resources/application.yml` with environment-variable-bound placeholders (no secrets)
- [x] Create `.env.example` documenting all required environment variables
- [x] Create `.gitignore` excluding: `.env`, `*.jar`, `build/`, `.idea/`, `*.class`

**Acceptance Criteria:**
- [x] Application starts with `./gradlew bootRun` against a PostgreSQL instance
- [x] Application fails fast with a clear error when required env vars (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`) are absent
- [x] No secrets in `application.yml` or any tracked file

**Automated Tests:**
- [x] `AuthApplicationTest` — `@SpringBootTest` context load test

---

### Phase 0.3 — Application Configuration (application.yml)

**Implementation Tasks:**

- [x] Configure datasource via environment variables:
  ```yaml
  spring.datasource.url: ${DB_URL}
  spring.datasource.username: ${DB_USERNAME}
  spring.datasource.password: ${DB_PASSWORD}
  spring.datasource.driver-class-name: org.postgresql.Driver
  ```
- [x] Configure JPA: `ddl-auto: validate`, `show-sql: false`, `dialect: PostgreSQLDialect`
- [x] Configure Flyway: `enabled: true`, `locations: classpath:db/migration`, `baseline-on-migrate: false`
- [x] Configure JWT validation properties block (public key or secret from auth module):
  ```yaml
  app.jwt.secret: ${JWT_SECRET}
  ```
- [x] Configure attachment settings:
  ```yaml
  app.attachments.max-file-size-mb: ${ATTACHMENT_MAX_SIZE_MB:10}
  app.attachments.max-per-transaction: ${ATTACHMENT_MAX_PER_TRANSACTION:5}
  app.attachments.allowed-types: pdf,png,jpg,jpeg
  ```
- [x] Configure upcoming bills default window:
  ```yaml
  app.dashboard.upcoming-bills-days: ${UPCOMING_BILLS_DAYS:7}
  app.dashboard.upcoming-bills-max-results: ${UPCOMING_BILLS_MAX_RESULTS:20}
  ```
- [x] Configure actuator: expose `health`, `info` only
- [x] Create `AppProperties.java` — `@ConfigurationProperties(prefix = "app")` bean with Attachments and Dashboard inner classes

**Acceptance Criteria:**
- [x] All sensitive values bound from environment variables; none hardcoded
- [x] Application startup fails with descriptive error when required properties are absent
- [x] `AppProperties` bean is available for injection in all service classes

**Automated Tests:**
- [x] `AppPropertiesTest` — verifies property binding from test environment

---

### Phase 0.4 — Test Infrastructure Setup

**Implementation Tasks:**

- [x] Create `src/test/resources/application-test.yml`:
  - Override datasource to use Testcontainers dynamic URL
  - Set `flyway.enabled: true`
  - Set `ddl-auto: validate`
  - Use a fixed test JWT secret
- [x] Create `PostgresTestContainerConfig.java`:
  - `@TestConfiguration`
  - Static `PostgreSQLContainer<>` instance (shared across tests)
  - Registers `DataSource` bean pointing to the container
- [x] Create `BaseIntegrationTest.java`:
  - `@SpringBootTest(webEnvironment = RANDOM_PORT)`
  - `@ActiveProfiles("test")`
  - Imports `PostgresTestContainerConfig`
  - Provides shared `MockMvc` setup
- [x] Create `BaseRepositoryTest.java`:
  - `@SpringBootTest` with Testcontainers PostgreSQL
- [x] Verify Testcontainers starts PostgreSQL and Flyway runs all migrations cleanly

**Acceptance Criteria:**
- [x] `BaseIntegrationTest` subclasses start the full context against a real PostgreSQL container
- [x] Flyway migrations run automatically in test context
- [x] Container is reused across tests in the same JVM

**Automated Tests:**
- [x] `TestContainerSmokeTest` — asserts the PostgreSQL container starts and accepts a connection

---

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

## Phase 3 — Security & User Scoping

**Objective:** Integrate with the auth module's JWT tokens. Establish the `currentUserId` extraction mechanism and enforce user-scoped access on all operations.

**Dependencies:** Phase 2 complete.

**Complexity:** Medium

### Phase 3.1 — JWT Filter & Security Configuration

**Implementation Tasks:**

- [x] Create `JwtAuthenticationFilter.java`:
  - Reads `Authorization: Bearer <token>` from each request
  - Validates the JWT signature using the shared secret / public key
  - Extracts `userId` (subject) and populates `SecurityContextHolder`
  - Returns 401 on invalid or missing tokens
- [x] Create `SecurityConfig.java` — `@Configuration @EnableMethodSecurity`:
  - Disable CSRF (stateless API)
  - Permit unauthenticated access to `/actuator/health`, `/actuator/info`, `/v3/api-docs/**`, `/swagger-ui/**`
  - Require authentication for all other endpoints
  - Register `JwtAuthenticationFilter` before `UsernamePasswordAuthenticationFilter`
- [x] Create `CurrentUser.java` annotation — `@AuthenticationPrincipal` shorthand for controller parameters

**Acceptance Criteria:**
- [x] Requests with a valid JWT proceed; requests without one are rejected with 401
- [x] `userId` is extractable from the security context in any service method

**Automated Tests:**
- [x] `JwtAuthenticationFilterTest` — unit tests for valid, expired, malformed, and missing tokens
- [x] `SecurityConfigIntegrationTest` — asserts public endpoints are accessible without auth; protected endpoints return 401

---

### Phase 3.2 — User Scoping Utilities

**Implementation Tasks:**

- [x] Create `SecurityUtils.java`:
  - `getCurrentUserId()` — extracts UUID from `SecurityContextHolder`
  - Throws `ForbiddenAccessException` if context is empty
- [x] Create `UserScopedRepository.java` marker interface — documents that all implementations must include `user_id` predicates
- [x] Create `CorrelationIdFilter.java` — sets a `X-Correlation-ID` on each request/response for log tracing

**Acceptance Criteria:**
- [x] `SecurityUtils.getCurrentUserId()` works in any service method during an authenticated request
- [x] Correlation ID is present in all log lines and response headers

**Automated Tests:**
- [x] `SecurityUtilsTest` — asserts correct UUID is returned from the security context

---

## Phase 4 — Account & Wallet Management

**Objective:** Implement full CRUD and lifecycle management for user accounts, including balance computation and transfer logic.

**Dependencies:** Phase 3 complete.

**Complexity:** Medium

### Phase 4.1 — Account Repository & Service

**Implementation Tasks:**

- [x] Create `AccountRepository.java` — extends `JpaRepository<Account, UUID>`:
  - `findAllByUserIdAndDeletedAtIsNull(UUID userId)`
  - `findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId)`
  - `existsByUserIdAndNameAndDeletedAtIsNull(UUID userId, String name)`
- [x] Create `AccountService.java` interface and `AccountServiceImpl.java`:
  - `createAccount(CreateAccountRequest, UUID userId)` — creates account + seed `MANUAL_ADJUSTMENT` transaction for initial balance
  - `listAccounts(UUID userId, boolean includeArchived)` — sorted by `displayOrder` then `createdAt`
  - `getAccount(UUID id, UUID userId)` — throws `ResourceNotFoundException` if not found
  - `editAccount(UUID id, EditAccountRequest, UUID userId)` — validates name uniqueness
  - `archiveAccount(UUID id, UUID userId)` — sets `archivedAt`; rejects if already archived
  - `unarchiveAccount(UUID id, UUID userId)`
  - `deleteAccount(UUID id, UUID userId)` — only allowed if no transactions beyond the seed record; else throws `BusinessRuleException`
  - `computeBalance(UUID accountId, UUID userId)` — sum of `PAID` transaction amounts with direction encoding
  - `manualAdjustment(ManualAdjustmentRequest, UUID userId)` — creates a `MANUAL_ADJUSTMENT` transaction for the delta

**Acceptance Criteria:**
- [x] Account name uniqueness per user enforced; `ConflictException` on duplicate
- [x] Archived account balance excluded from portfolio aggregations
- [x] Deletion rejected with 422 if account has non-seed transactions

**Automated Tests:**
- [x] `AccountServiceTest` — unit tests for each service method with mocked repositories
- [x] `AccountIntegrationTest` — full lifecycle against Testcontainers PostgreSQL

---

### Phase 4.2 — Account Controller

**Implementation Tasks:**

- [x] Create `AccountController.java` — `@RestController @RequestMapping("/api/v1/accounts")`:
  - `POST /` → `createAccount` → 201
  - `GET /` → `listAccounts` (query param `includeArchived`) → 200
  - `GET /{id}` → `getAccount` → 200
  - `PUT /{id}` → `editAccount` → 200
  - `POST /{id}/archive` → `archiveAccount` → 200
  - `POST /{id}/unarchive` → `unarchiveAccount` → 200
  - `DELETE /{id}` → `deleteAccount` → 204
  - `POST /{id}/adjust` → `manualAdjustment` → 200
- [x] Create request DTOs: `CreateAccountRequest`, `EditAccountRequest`, `ManualAdjustmentRequest`
- [x] Create response DTO: `AccountResponse` (never expose the JPA entity)

**Acceptance Criteria:**
- [x] All endpoints require a valid JWT
- [x] `userId` always sourced from the JWT; never from the request body
- [x] `AccountResponse` includes computed balance but never the JPA entity

**Automated Tests:**
- [x] `AccountControllerTest` — `@WebMvcTest` for all endpoints; validates HTTP status and response body structure

---

### Phase 4.3 — Transfer Between Accounts

**Implementation Tasks:**

- [x] Add `createTransfer(TransferRequest, UUID userId)` to `AccountService`:
  - Validates both accounts belong to `userId`
  - Validates source ≠ destination
  - Validates neither account is archived
  - Creates two linked `TRANSFER` transactions atomically with the same `transferGroupId`
- [x] Add `POST /api/v1/accounts/transfers` endpoint to `AccountController`
- [x] Add `DELETE /api/v1/accounts/transfers/{groupId}` — deletes both legs atomically
- [x] Create request DTO: `TransferRequest`

**Acceptance Criteria:**
- [x] Both legs created atomically; if either fails, neither is persisted
- [x] Transfer nets to zero in portfolio balance calculations
- [x] Deleting one leg individually rejected with 422

**Automated Tests:**
- [x] `TransferIntegrationTest` — asserts both legs are created; asserts portfolio balance is unchanged

---

## Phase 5 — Transaction Management

**Objective:** Implement the full transaction lifecycle: create, edit, delete, status transitions, list with filters, and attachment management.

**Dependencies:** Phase 4 complete.

**Complexity:** High

### Phase 5.1 — Transaction Repository

**Implementation Tasks:**

- [x] Create `TransactionRepository.java` — `JpaRepository<Transaction, UUID>`:
  - `findByIdAndUserId(UUID id, UUID userId)`
  - `findAllByUserId(UUID userId, Pageable pageable)`
  - `findAllByAccountIdAndUserId(UUID accountId, UUID userId, Pageable pageable)`
  - Custom JPQL query for filtered search (account, type, status, category, date range, amount range, text search)
  - `existsByAccountIdAndUserIdAndStatusNotIn(UUID accountId, UUID userId, List<TransactionStatus> statuses)` — for account deletion guard
  - `sumPaidAmountByAccountIdAndUserId(UUID accountId, UUID userId)` — for balance computation

---

### Phase 5.2 — Transaction Service

**Implementation Tasks:**

- [x] Create `TransactionService.java` and `TransactionServiceImpl.java`:
  - `createTransaction(CreateTransactionRequest, UUID userId)` → 201
  - `editTransaction(UUID id, EditTransactionRequest, UUID userId)` — detaches from installment series if part of one
  - `deleteTransaction(UUID id, UUID userId)` — rejects individual deletion of a transfer leg with 422
  - `markAsPaid(UUID id, MarkAsPaidRequest, UUID userId)` — validates `PENDING`/`OVERDUE` → `PAID` transition
  - `cancelTransaction(UUID id, UUID userId)` — sets `cancelledAt`; validates not already cancelled
  - `listTransactions(TransactionFilterRequest, UUID userId, Pageable pageable)` — filtered + paginated
  - `getTransaction(UUID id, UUID userId)` — full detail
  - `detectOverdue(UUID userId)` — transitions eligible `PENDING` → `OVERDUE` (for scheduled/on-demand use)
- [x] Enforce status transition rules; throw `BusinessRuleException` on invalid transitions
- [x] Ensure `BigDecimal` arithmetic throughout; never `double` or `float`
- [x] Apply category rules at creation time

**Acceptance Criteria:**
- [x] Invalid status transitions rejected with 422
- [x] `CANCELLED` transactions never affect balance
- [x] All monetary arithmetic uses `BigDecimal` with `HALF_UP` rounding
- [x] Category auto-assignment applied at creation when a matching rule exists

**Automated Tests:**
- [x] `TransactionServiceTest` — unit tests for all methods
- [x] `TransactionStatusTransitionTest` — asserts valid and invalid transitions
- [x] `BalanceConsistencyTest` — known transaction sequences verified against expected balance

---

### Phase 5.3 — Transaction Controller

**Implementation Tasks:**

- [x] Create `TransactionController.java` — `@RestController @RequestMapping("/api/v1/transactions")`:
  - `POST /` → 201
  - `GET /` → paginated list with filter query params → 200
  - `GET /{id}` → full detail → 200
  - `PUT /{id}` → edit → 200
  - `DELETE /{id}` → 204
  - `POST /{id}/pay` → mark as paid → 200
  - `POST /{id}/cancel` → cancel → 200
- [x] Create request DTOs with Jakarta Validation: `CreateTransactionRequest`, `EditTransactionRequest`, `MarkAsPaidRequest`, `TransactionFilterRequest`
- [x] Create response DTOs: `TransactionSummaryResponse`, `TransactionDetailResponse`

**Acceptance Criteria:**
- [x] Validation failures return 400 with field-level error body
- [x] Entities never leak outside the service boundary
- [x] `CANCELLED` transactions excluded from list by default; `includeCancelled=true` param to include

**Automated Tests:**
- [x] `TransactionControllerTest` — `@WebMvcTest` for all endpoints

---

### Phase 5.4 — Attachment Management

**Implementation Tasks:**

- [x] Create `AttachmentRepository.java`
- [x] Create `AttachmentService.java`:
  - `attach(UUID transactionId, MultipartFile[] files, UUID userId)` — validates file type, size, and per-transaction limit
  - `deleteAttachment(UUID attachmentId, UUID userId)`
  - `getAttachments(UUID transactionId, UUID userId)`
  - Storage: persist file to configurable storage (local filesystem for dev, S3-compatible for prod) using a `StoragePort` interface
  - Never expose raw file paths in API responses — return signed access references only
- [x] Create `StoragePort.java` interface + `LocalFileStorageAdapter.java` implementation (dev/test)
- [x] Add attachment endpoints to `TransactionController`:
  - `POST /{id}/attachments` → 201
  - `GET /{id}/attachments` → 200
  - `DELETE /{id}/attachments/{attachmentId}` → 204

**Acceptance Criteria:**
- [x] Only PDF, PNG, JPG, JPEG accepted; others → 400
- [x] File size above configured max → 422
- [x] Per-transaction limit enforced → 422
- [x] Storage keys never exposed in API responses

**Automated Tests:**
- [x] `AttachmentServiceTest` — file validation, limit enforcement

---

## Phase 6 — Installment Transactions

**Objective:** Implement installment series creation, series-wide and individual editing, early settlement, and advance payment.

**Dependencies:** Phase 5 complete.

**Complexity:** High

### Phase 6.1 — Installment Service

**Implementation Tasks:**

- [x] Create `InstallmentRepository.java` and `InstallmentSeriesRepository.java`
- [x] Create `InstallmentService.java` and `InstallmentServiceImpl.java`:
  - `createInstallmentSeries(CreateInstallmentRequest, UUID userId)`:
    - Creates `InstallmentSeries` master record
    - Generates individual `Transaction` records for each installment
    - Amount split: `totalAmount / totalInstallments` with remainder on the last installment
    - First installment: `PAID` if firstPaymentDate ≤ today; remainder `PENDING`
    - Monthly `paymentDate` progression from `firstPaymentDate`
  - `editSeries(UUID seriesId, EditSeriesRequest, UUID userId)` — updates description, notes, category, account on all `PENDING`/`OVERDUE` installments
  - `editInstallment(UUID transactionId, EditInstallmentRequest, UUID userId)` — marks `detached = true` on the transaction
  - `earlySettlement(UUID seriesId, EarlySettlementRequest, UUID userId)`:
    - Cancels all remaining `PENDING`/`OVERDUE` installments
    - Creates one `PAID` settlement transaction linked to the series
    - Sets `series.settled = true`, `series.settledAt = now()`
  - `advanceInstallments(AdvanceInstallmentRequest, UUID userId)` — moves payment dates, optionally adjusts amounts

**Acceptance Criteria:**
- [x] Installment amounts sum to `totalAmount` exactly (no floating-point rounding drift)
- [x] Remainder handling deterministic (last installment)
- [x] Detached installments excluded from series-wide edit
- [x] Early settlement atomic: all cancellations + settlement creation in one transaction

**Automated Tests:**
- [x] `InstallmentAmountSplitTest` — verifies exact sum and remainder assignment across multiple total/count combinations
- [x] `EarlySettlementIntegrationTest` — asserts cancellations and settlement creation atomicity

---

### Phase 6.2 — Installment Controller

**Implementation Tasks:**

- [x] Create `InstallmentController.java` — `@RestController @RequestMapping("/api/v1/installments")`:
  - `POST /` → create series → 201
  - `PUT /series/{seriesId}` → edit series → 200
  - `PUT /{transactionId}` → edit individual installment → 200
  - `POST /series/{seriesId}/settle` → early settlement → 200
  - `POST /advance` → advance installments → 200

**Automated Tests:**
- [x] `InstallmentControllerTest` — HTTP status and response body validation

---

## Phase 7 — Recurring Transactions

**Objective:** Implement recurrence rules, instance generation, pausing, and deletion strategies.

**Dependencies:** Phase 5 complete.

**Complexity:** Medium

### Phase 7.1 — Recurrence Service

**Implementation Tasks:**

- [x] Create `RecurrenceRepository.java`
- [x] Create `RecurrenceService.java` and `RecurrenceServiceImpl.java`:
  - `createRecurrence(CreateRecurrenceRequest, UUID userId)`:
    - Creates `RecurrenceRule`
    - Generates first instance immediately
    - Pre-generates instances for the next N periods (configurable) or marks `nextOccurrenceDate` for lazy generation
  - `editSeries(UUID ruleId, EditRecurrenceRequest, UUID userId)` — updates future `PENDING` instances; updates master rule
  - `pauseRecurrence(UUID ruleId, PauseRequest, UUID userId)` — sets `status = PAUSED`; cancels future `PENDING` instances for the pause window
  - `resumeRecurrence(UUID ruleId, UUID userId)` — restores `ACTIVE` status; regenerates instances from resume date
  - `deleteRecurrence(UUID ruleId, DeleteRecurrenceStrategy, UUID userId)`:
    - `FUTURE_ONLY`: cancels future `PENDING` instances; soft-deletes rule
    - `ALL`: cancels all `PENDING` instances; soft-deletes rule
    - Never touches `PAID` instances
- [x] Create `RecurrenceGeneratorService.java` — stateless utility that computes the next occurrence date given a frequency and a base date

**Acceptance Criteria:**
- [x] `PAID` instances never modified by any recurrence operation
- [x] Pause cancels exactly the pending instances in the pause window
- [x] `RecurrenceGeneratorService` handles month-end edge cases (e.g., Jan 31 → Feb 28)

**Automated Tests:**
- [x] `RecurrenceGeneratorServiceTest` — edge cases for all frequencies including month-end dates
- [x] `RecurrenceServiceIntegrationTest` — full lifecycle

---

### Phase 7.2 — Recurrence Controller

**Implementation Tasks:**

- [x] Create `RecurrenceController.java` — `@RestController @RequestMapping("/api/v1/recurrences")`:
  - `POST /` → 201
  - `GET /` → list rules → 200
  - `GET /{id}` → get rule → 200
  - `PUT /{id}` → edit series → 200
  - `POST /{id}/pause` → 200
  - `POST /{id}/resume` → 200
  - `DELETE /{id}` → (strategy as query param) → 200

**Automated Tests:**
- [x] `RecurrenceControllerTest` — HTTP validation

---

## Phase 8 — Category Management

**Objective:** Implement full category lifecycle: list, create, edit, hide, archive, auto-suggest, and category rules.

**Dependencies:** Phase 3 complete.

**Complexity:** Medium

### Phase 8.1 — Category Service

**Implementation Tasks:**

- [x] Create `CategoryRepository.java`:
  - `findAllSystemCategories()` — `user_id IS NULL`
  - `findAllByUserId(UUID userId)` — user-defined categories
  - `existsByUserIdAndParentIdAndName(UUID userId, UUID parentId, String name)`
- [x] Create `CategoryService.java` and `CategoryServiceImpl.java`:
  - `listCategories(UUID userId, boolean includeHidden, boolean includeArchived)` — returns system + user categories; nested subcategories
  - `createCategory(CreateCategoryRequest, UUID userId)` — validates max depth (root or subcategory only); validates name uniqueness within parent scope
  - `editCategory(UUID id, EditCategoryRequest, UUID userId)` — only user-defined categories; validates name uniqueness
  - `setHidden(UUID id, boolean hidden, UUID userId)` — works on system and user categories
  - `archiveCategory(UUID id, UUID userId)` — archives parent and all subcategories; user-defined only
  - `unarchiveCategory(UUID id, UUID userId)`
  - `suggestCategory(String description, UUID userId)` — frequency-based suggestion from transaction history; falls back to most-used categories
  - `createRule(CreateCategoryRuleRequest, UUID userId)`
  - `listRules(UUID userId)`
  - `deleteRule(UUID id, UUID userId)`

**Acceptance Criteria:**
- [x] System categories cannot be archived; `BusinessRuleException` on attempt
- [x] Subcategory nesting limited to two levels; third-level creation → 422
- [x] `archiveCategory` propagates to all subcategories atomically
- [x] `suggestCategory` returns at least one suggestion when the user has transaction history

**Automated Tests:**
- [x] `CategoryServiceTest` — unit tests for all methods
- [x] `CategorySuggestionTest` — asserts suggestion accuracy from seeded history

---

### Phase 8.2 — Category Controller

**Implementation Tasks:**

- [x] Create `CategoryController.java` — `@RestController @RequestMapping("/api/v1/categories")`:
  - `GET /` → list (with `includeHidden`, `includeArchived` params) → 200
  - `POST /` → create → 201
  - `PUT /{id}` → edit → 200
  - `POST /{id}/hide` and `POST /{id}/show` → 200
  - `POST /{id}/archive` and `POST /{id}/unarchive` → 200
  - `GET /suggest?description=...` → suggestions → 200
  - `POST /rules` → create rule → 201
  - `GET /rules` → list rules → 200
  - `DELETE /rules/{id}` → 204

**Automated Tests:**
- [x] `CategoryControllerTest` — HTTP validation for all endpoints

---

## Phase 9 — Credit Card Management

**Objective:** Implement credit card registration, charge recording, invoice lifecycle, payment, limit tracking, and spending analysis.

**Dependencies:** Phase 5 complete.

**Complexity:** High

### Phase 9.1 — Credit Card Service

**Implementation Tasks:**

- [x] Create `CreditCardRepository.java`, `InvoiceRepository.java`, `InvoiceItemRepository.java`
- [x] Create `CreditCardService.java` and `CreditCardServiceImpl.java`:
  - `createCard(CreateCardRequest, UUID userId)`:
    - Validates `closingDay` and `dueDay` 1–28
    - Creates card and opens first invoice for the current billing cycle
  - `listCards(UUID userId)`
  - `editCard(UUID id, EditCardRequest, UUID userId)`
  - `archiveCard(UUID id, UUID userId)`
  - `recordCharge(UUID cardId, RecordChargeRequest, UUID userId)`:
    - Assigns charge to correct invoice based on `competenceDate` vs `closingDay`
    - Updates invoice `totalAmount`
    - Updates card `usedLimit`
  - `getInvoice(UUID cardId, String referenceMonth, UUID userId)` — with paginated charges
  - `payInvoice(UUID invoiceId, PayInvoiceRequest, UUID userId)`:
    - Full payment → `PAID`
    - Partial payment → `PARTIAL`; creates `REVOLVING` item on next invoice
    - Creates debit transaction on source account
  - `getLimitUsage(UUID cardId, UUID userId)` — computed in real time
  - `getSpendingByCategory(UUID cardId, DateRange, UUID userId)`
- [x] Create `InvoiceCycleCalculator.java` — utility determining which invoice a charge belongs to based on `closingDay`

**Acceptance Criteria:**
- [x] Charge assigned to correct invoice; post-closing charges go to next cycle
- [x] Partial payment creates revolving item on next invoice atomically
- [x] `getLimitUsage` reflects real-time state after every charge or payment
- [x] `closingDay`/`dueDay` validated 1–28; `BusinessRuleException` otherwise

**Automated Tests:**
- [x] `InvoiceCycleCalculatorTest` — edge cases around month boundaries and closing day
- [x] `CreditCardServiceIntegrationTest` — full charge → invoice → payment lifecycle
- [x] `PartialPaymentTest` — asserts revolving item creation and next invoice update

---

### Phase 9.2 — Credit Card Controller

**Implementation Tasks:**

- [x] Create `CreditCardController.java` — `@RestController @RequestMapping("/api/v1/cards")`:
  - `POST /` → 201
  - `GET /` → 200
  - `PUT /{id}` → 200
  - `POST /{id}/archive` → 200
  - `POST /{id}/charges` → record charge → 201
  - `GET /{id}/invoices/{referenceMonth}` → invoice detail → 200
  - `POST /invoices/{invoiceId}/pay` → pay invoice → 200
  - `GET /{id}/limit` → limit usage → 200
  - `GET /{id}/spending` → spending by category (with `from`, `to` params) → 200

**Automated Tests:**
- [x] `CreditCardControllerTest` — HTTP validation for all endpoints

---

## Phase 10 — Dashboard & Reporting

**Objective:** Implement all dashboard aggregation endpoints and chart data endpoints.

**Dependencies:** Phases 4–9 complete.

**Complexity:** Medium

### Phase 10.1 — Dashboard Service

**Implementation Tasks:**

- [ ] Create `DashboardService.java` and `DashboardServiceImpl.java`:
  - `getOverviewMetrics(UUID userId)`:
    - Total balance: sum of `PAID` transactions across all non-archived, non-investment accounts
    - Net worth: total balance + investment account balances
    - Monthly income: `PAID` `INCOME` transactions with `paymentDate` in current calendar month
    - Monthly expenses: `PAID` `EXPENSE` transactions with `paymentDate` in current calendar month
    - Monthly savings: income − expenses
    - Cash flow: configurable window (default: current month)
    - All amounts as `BigDecimal`; no floating-point
  - `getCategoryPieChart(UUID userId, DateRange, UUID accountId, TransactionType)`:
    - `PAID` transactions only; grouped by category; percentage of total; sorted by amount desc
    - Uncategorized → `UNCATEGORIZED` bucket
  - `getMonthlyBarChart(UUID userId, int months, UUID accountId)`:
    - One entry per month; months with no transactions filled with zero values; ordered chronologically
  - `getNetWorthEvolution(UUID userId, DateRange, Granularity)`:
    - Replays transaction history up to each snapshot date
  - `getMonthlyComparison(UUID userId, String month1, String month2)`:
    - Income, expenses, savings, category breakdown for each; delta values computed
  - `getUpcomingBills(UUID userId, int daysAhead)` — `PENDING`/`OVERDUE`, `paymentDate ≤ today + daysAhead`
  - `getUpcomingInvoices(UUID userId, int daysAhead)` — invoices with `dueDate ≤ today + daysAhead`, status `CLOSED`/`PARTIAL`/`OVERDUE`
  - `getLargestExpenses(UUID userId, DateRange, int limit)`
  - `getRecentTransactions(UUID userId, int limit)`

**Acceptance Criteria:**
- [ ] All metrics scoped to the authenticated user; no cross-user data
- [ ] All monetary values use `BigDecimal` string representation in responses
- [ ] Months with no transactions included with zero values in bar chart
- [ ] Net worth evolution replays history accurately

**Automated Tests:**
- [ ] `DashboardServiceTest` — unit tests with seeded transaction data for each metric
- [ ] `OverviewMetricsIntegrationTest` — asserts correct totals against known transaction sequences

---

### Phase 10.2 — Dashboard Controller

**Implementation Tasks:**

- [ ] Create `DashboardController.java` — `@RestController @RequestMapping("/api/v1/dashboard")`:
  - `GET /overview` → overview metrics → 200
  - `GET /charts/categories` → pie chart data → 200
  - `GET /charts/monthly` → bar chart data → 200
  - `GET /charts/net-worth` → net worth evolution → 200
  - `GET /charts/comparison` → monthly comparison → 200
  - `GET /widgets/upcoming-bills` → 200
  - `GET /widgets/upcoming-invoices` → 200
  - `GET /widgets/largest-expenses` → 200
  - `GET /widgets/recent-transactions` → 200

**Automated Tests:**
- [ ] `DashboardControllerTest` — HTTP validation for all endpoints

---

## Phase 11 — Overdue Detection & Scheduled Jobs

**Objective:** Implement automatic overdue status transitions and recurring transaction instance generation as scheduled background tasks.

**Dependencies:** Phases 5, 7 complete.

**Complexity:** Low

### Phase 11.1 — Scheduled Services

**Implementation Tasks:**

- [ ] Create `OverdueDetectionScheduler.java` — `@Scheduled(cron = "0 0 1 * * *")` (daily at 01:00):
  - Calls `TransactionService.detectOverdue()` for all users
  - Transitions `PENDING` → `OVERDUE` where `paymentDate < today`
  - Logs count of transitions; never logs financial content
- [ ] Create `RecurrenceGenerationScheduler.java` — `@Scheduled(cron = "0 0 2 * * *")` (daily at 02:00):
  - Generates upcoming instances for all active recurrence rules whose `nextOccurrenceDate ≤ today + lookahead`
  - Updates `nextOccurrenceDate` after generation

**Acceptance Criteria:**
- [ ] Overdue detection runs daily; only affects `PENDING` with `paymentDate < today`
- [ ] Recurrence generation is idempotent (does not create duplicates if run twice)

**Automated Tests:**
- [ ] `OverdueDetectionSchedulerTest` — asserts correct transitions for a seeded dataset
- [ ] `RecurrenceGenerationSchedulerTest` — asserts no duplicates on double-run

---

## Phase 12 — API Documentation (OpenAPI / Swagger)

**Objective:** Expose self-documenting OpenAPI specification via Springdoc.

**Dependencies:** Phases 4–10 complete.

**Complexity:** Low

### Phase 12.1 — OpenAPI Configuration

**Implementation Tasks:**

- [ ] Add `springdoc-openapi-starter-webmvc-ui` to `build.gradle.kts`
- [ ] Create `OpenApiConfig.java` — `@Configuration`:
  - Set API title: `Cash Control API`
  - Set version: `v1`
  - Set description aligned with project-description.md
  - Add JWT `BearerAuth` security scheme
  - Apply global security requirement so all endpoints show the lock icon in Swagger UI
- [ ] Annotate all controllers with `@Tag(name = "...", description = "...")`
- [ ] Annotate key endpoints with `@Operation(summary = "...")` and `@ApiResponse` codes
- [ ] Annotate all DTOs with `@Schema` where field-level description adds value

**Acceptance Criteria:**
- [ ] Swagger UI accessible at `/swagger-ui/index.html`
- [ ] OpenAPI JSON available at `/v3/api-docs`
- [ ] All endpoints visible with correct HTTP methods and response codes

**Automated Tests:**
- [ ] `OpenApiSmokeTest` — asserts `/v3/api-docs` returns 200 and contains expected path count

---

## Phase 13 — Observability & Logging

**Objective:** Implement structured JSON logging with correlation IDs. Financial data must never appear in logs.

**Dependencies:** Phase 3 complete.

**Complexity:** Low

### Phase 13.1 — Structured Logging Setup

**Implementation Tasks:**

- [ ] Add `logstash-logback-encoder` to `build.gradle.kts`
- [ ] Create `logback-spring.xml`:
  - JSON appender for production (`!local` profile)
  - Console appender for local development
  - Include `correlationId` MDC field in all log lines
- [ ] Create `LogSanitizationGuard.java` — utility that enforces no financial content in logs (verifiable via code review; enforced by convention)
- [ ] Update `CorrelationIdFilter.java` to set `MDC.put("correlationId", id)` on each request
- [ ] Log only: event type, user UUID, resource UUID, correlation ID, HTTP method, path, status code, duration
- [ ] Never log: amounts, descriptions, account names, category names, tag values

**Acceptance Criteria:**
- [ ] All log lines in production contain `correlationId`
- [ ] No financial content (amounts, descriptions) in any log output at any level
- [ ] Log format is JSON in non-local profiles

**Automated Tests:**
- [ ] `CorrelationIdFilterTest` — asserts `X-Correlation-ID` is present in the response header

---

## Phase 14 — Testing, Docker & CI/CD

**Objective:** Complete the test suite covering all domain rules, containerize the application, and configure CI/CD.

**Dependencies:** All previous phases complete.

**Complexity:** Medium

### Phase 14.1 — Comprehensive Test Suite

**Implementation Tasks:**

- [ ] Verify unit test coverage for all service methods
- [ ] Verify integration tests for each major domain: accounts, transactions, installments, recurrences, categories, credit cards, dashboard
- [ ] Add boundary tests:
  - `BigDecimal` precision in installment splits (various total/count combinations)
  - Balance consistency after concurrent-scenario transaction sequences
  - Overdue detection edge cases (same day, past day, future day)
  - Invoice cycle edge cases (month boundary with `closingDay = 31`)
- [ ] Verify all integration tests use Testcontainers PostgreSQL (no mocked repositories for core financial flows)
- [ ] Verify no `double` or `float` used for monetary values anywhere in the codebase (`./gradlew test` includes a custom lint check or inspection)

**Acceptance Criteria:**
- [ ] All tests pass on `./gradlew test`
- [ ] Integration tests hit a real PostgreSQL container
- [ ] No financial floating-point arithmetic anywhere in the production code

**Automated Tests:**
- [ ] Full test suite — all unit and integration tests

---

### Phase 14.2 — Docker & Docker Compose

**Implementation Tasks:**

- [ ] Create `Dockerfile` — multi-stage build:
  - Stage 1 (`builder`): `gradle:jdk25` — runs `./gradlew bootJar`
  - Stage 2 (`runtime`): `eclipse-temurin:25-jre-alpine` — copies JAR from builder
  - Expose port 8080; non-root user; health check via `/actuator/health`
- [ ] Create `.dockerignore` — exclude: `.git`, `build/`, `.gradle/`, `*.md`, `.env`
- [ ] Create `docker-compose.yml`:
  - `app` service: builds from `Dockerfile`; depends on `postgres`; env vars from `.env`
  - `postgres` service: `postgres:18`; volume for data persistence; health check
- [ ] Create `docker-compose.override.yml` for local development overrides

**Acceptance Criteria:**
- [ ] `docker compose up` starts both services and application is healthy at `/actuator/health`
- [ ] All configuration injected via environment variables; no secrets in Docker files

**Automated Tests:**
- [ ] Docker build smoke test in CI

---

### Phase 14.3 — CI/CD Pipeline

**Implementation Tasks:**

- [ ] Create `.github/workflows/ci.yml`:
  - Trigger: push to `main`, pull requests to `main`
  - Job `build-and-test`:
    - `actions/checkout@v4`
    - `actions/setup-java@v4` with Java 25 and Gradle cache
    - `./gradlew build` — compile and run all tests (Testcontainers spins up PostgreSQL in CI)
    - Upload test reports as artifacts on failure
  - Job `security-check`:
    - OWASP Dependency Check or equivalent
  - Job `docker-build`:
    - Build Docker image; do not push unless on `main` tag
- [ ] Ensure CI does not require any external secrets beyond the test JWT key

**Acceptance Criteria:**
- [ ] PR build fails if any test fails
- [ ] PR build fails if Docker build fails
- [ ] Test reports are available as CI artifacts

**Automated Tests:**
- [ ] All CI jobs green on a clean branch

---

### Phase 14.4 — Verification

**Implementation Tasks:**

- [ ] Run `./gradlew test` — all tests pass
- [ ] Run `docker compose up --build` — application starts; `/actuator/health` returns `UP`
- [ ] Smoke-test all major endpoints via Insomnia or curl:
  - Create account → adjust balance → create transaction → create installment series → create recurrence → create credit card → record charge → pay invoice → dashboard overview
- [ ] Verify no `double`/`float` arithmetic for monetary values (`grep -r "double\|float" src/main/java` returns zero hits in financial logic)
- [ ] Verify no financial content in logs during the smoke test run
- [ ] Confirm all Flyway migrations apply cleanly on a fresh PostgreSQL 18 container

**Acceptance Criteria:**
- [ ] All tests pass
- [ ] Docker smoke test passes
- [ ] All major flows work end-to-end
- [ ] Zero floating-point monetary arithmetic

---

*End of Implementation Roadmap — cash-control-api v1*