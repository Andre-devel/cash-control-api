# Implementation Roadmap — java-auth-template

**Stack:** Java 25 · Spring Boot 4.0.6 · Spring Security 7 · PostgreSQL 18 · Flyway · JPA/Hibernate · Gradle  
**Architecture:** Stateless JWT · RBAC · Granular permissions (`resource:action`) · Google OAuth2 · LGPD-aligned  
**Token model:** Pure stateless JWT access tokens — NO refresh tokens, NO session tables, NO token persistence  
**Generated:** 2026-05-16  
**Status legend:** `[x]` = implemented · `[ ]` = pending

---

## Codebase Inspection Summary

| Area | Status |
|---|---|
| Gradle build files | `[x]` Present — `build.gradle.kts`, `settings.gradle.kts`, Gradle 9.5.1 wrapper |
| Spring Boot application entry point | `[x]` Present — `AuthApplication.java` |
| Source code (`src/`) | `[x]` Present — full implementation (Phases 2–15) |
| Flyway migrations | `[x]` Present — V1–V8 migrations applied |
| Configuration files | `[x]` Present — `application.yml`, `application-test.yml`, `logback-spring.xml` |
| Docker artifacts | `[x]` Present — `Dockerfile`, `docker-compose.yml`, `.dockerignore` |
| Test infrastructure | `[x]` Present — 79 test suites, 614 tests, all passing |
| CI/CD pipeline | `[x]` Present — `.github/workflows/ci.yml` (build, test, security check, Docker build) |

**All phases complete.** Full implementation through Phase 16 (Verification — Docker, Testcontainers, 614 tests passing).

---

## Implementation Strategy

Phases are ordered by dependency: infrastructure → database → domain → security → services → controllers → OAuth2 → hardening → observability → API docs → testing → CI/CD. Security-critical foundations (JWT infrastructure, password hashing, filter chain) are prioritized before feature controllers. Each phase produces a self-contained, testable vertical slice.

---

## Phase 0 — Project Bootstrap & Build Infrastructure

**Objective:** Produce a compilable, runnable Spring Boot application skeleton with all dependencies declared, configuration externalized, and the test harness bootstrapped.

**Dependencies:** None — this is the starting point.

**Complexity:** Low

### Phase 0.1 — Gradle Build Configuration

**Implementation Tasks:**

- [x] Create `settings.gradle.kts` — set `rootProject.name = "java-auth-template"`
- [x] Create `build.gradle.kts` with the following dependency blocks:
  - `org.springframework.boot` plugin version `4.0.6`
  - `io.spring.dependency-management` plugin
  - `java` plugin targeting Java 25 (toolchain `JavaLanguageVersion.of(25)`)
  - Spring Boot Starter Web
  - Spring Boot Starter Security
  - Spring Boot Starter Data JPA
  - Spring Boot Starter Validation
  - Spring Boot Starter OAuth2 Client
  - Spring Boot Starter Mail
  - Spring Boot Starter Actuator
  - `spring-security-oauth2-jose` (JWT support)
  - `org.flywaydb:flyway-core` + `flyway-database-postgresql`
  - `org.postgresql:postgresql`
  - Lombok + annotation processor
  - `io.jsonwebtoken:jjwt-api` + `jjwt-impl` + `jjwt-jackson` (v0.12.6)
  - `org.springframework.boot:spring-boot-starter-test` (test scope)
  - `org.testcontainers:postgresql` (test scope)
  - `org.testcontainers:junit-jupiter` (test scope)
  - `org.springdoc:springdoc-openapi-starter-webmvc-ui` (v2.8.8)
  - `org.bouncycastle:bcprov-jdk18on` (v1.80, Argon2id support)
  - `net.logstash.logback:logstash-logback-encoder` (v8.0, JSON logging)
- [x] Configure `compileJava.options.annotationProcessorPath` for Lombok
- [x] Configure `test { useJUnitPlatform() }`
- [x] Create `gradle/wrapper/gradle-wrapper.properties` targeting Gradle 9.5.1 (upgraded from 8.x — Gradle 8.13 does not parse Java 25 version strings in its embedded Kotlin compiler)
- [x] Verify `./gradlew build` succeeds on an empty source set

**Acceptance Criteria:**
- [x] `./gradlew dependencies` resolves without conflicts
- [x] `./gradlew compileJava` succeeds
- [x] `./gradlew test` runs with zero tests and exits 0
- [x] No dependency version conflicts in the resolution graph

**Automated Tests:**
- [x] Gradle build smoke test (CI step — not a JUnit test)

---

### Phase 0.2 — Application Entry Point & Package Structure

**Implementation Tasks:**

- [x] Create package root: `com.example.auth`
- [x] Create `AuthApplication.java` with `@SpringBootApplication`
- [x] Establish enforced package structure:
  ```
  com.example.auth
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
  ├── security/        — JWT filter, UserDetailsService, SecurityConfig
  ├── util/            — Masking, sanitization, correlation ID utilities
  └── audit/           — Audit event service and taxonomy
  ```
- [x] Create `src/main/resources/application.yml` with environment-variable-bound placeholders (no secrets)
- [x] Create `src/main/resources/application-dev.yml` for local development overrides
- [x] Create `.env.example` documenting all required environment variables with descriptions but no values
- [x] Create `.gitignore` excluding: `.env`, `*.jar`, `build/`, `.idea/`, `*.class`

**Acceptance Criteria:**
- [x] Application starts with `./gradlew bootRun` against a PostgreSQL instance
- [x] Application fails fast with a clear error when required env vars (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`) are absent
- [x] No secrets in `application.yml` or any tracked file

**Automated Tests:**
- [x] `AuthApplicationTest` — `@SpringBootTest` context load test (fails if beans wire incorrectly)

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
- [x] Configure JWT properties block:
  ```yaml
  app.jwt.secret: ${JWT_SECRET}
  app.jwt.expiration-minutes: ${JWT_EXPIRATION_MINUTES:15}
  ```
- [x] Configure brute-force properties:
  ```yaml
  app.security.max-failed-attempts: ${MAX_FAILED_ATTEMPTS:5}
  app.security.lockout-duration-minutes: ${LOCKOUT_DURATION_MINUTES:15}
  ```
- [x] Configure password reset and verification TTLs:
  ```yaml
  app.security.password-reset-expiry-minutes: ${PASSWORD_RESET_EXPIRY_MINUTES:60}
  app.security.email-verification-expiry-hours: ${EMAIL_VERIFICATION_EXPIRY_HOURS:24}
  ```
- [x] Configure mail properties via environment variables
- [x] Configure OAuth2 Google client:
  ```yaml
  spring.security.oauth2.client.registration.google.client-id: ${GOOGLE_CLIENT_ID}
  spring.security.oauth2.client.registration.google.client-secret: ${GOOGLE_CLIENT_SECRET}
  spring.security.oauth2.client.registration.google.scope: openid,email,profile
  ```
- [x] Create `AppProperties.java` — `@ConfigurationProperties(prefix = "app")` bean for type-safe config access
- [x] Configure actuator: expose `health`, `info` only; disable all others

**Acceptance Criteria:**
- [x] All sensitive values bound from environment variables; none hardcoded
- [x] Application startup fails with descriptive `ConfigurationPropertiesBindException` when required properties are absent
- [x] `AppProperties` bean is available for injection in all service classes

**Automated Tests:**
- [x] `AppPropertiesTest` — verifies property binding from test environment

---

### Phase 0.4 — Test Infrastructure Setup

**Implementation Tasks:**

- [x] Create `src/test/resources/application-test.yml`:
  - Override datasource to use Testcontainers dynamic URL
  - Set `flyway.enabled: true` so migrations run in tests
  - Set `ddl-auto: validate`
  - Use a fixed test JWT secret
- [x] Create `PostgresTestContainerConfig.java`:
  - `@TestConfiguration`
  - Starts `PostgreSQLContainer<>` once per test suite (static instance)
  - Registers `DataSource` bean pointing to container
- [x] Create `BaseIntegrationTest.java`:
  - `@SpringBootTest(webEnvironment = RANDOM_PORT)`
  - `@ActiveProfiles("test")`
  - Imports `PostgresTestContainerConfig`
  - Provides shared `TestRestTemplate` and `MockMvc`
- [x] Create `BaseRepositoryTest.java`:
  - `@DataJpaTest`
  - Uses Testcontainers PostgreSQL
  - Does not load the full application context
- [x] Verify Testcontainers starts PostgreSQL and Flyway runs all migrations cleanly

**Acceptance Criteria:**
- [x] `BaseIntegrationTest` subclasses start the full application context against a real PostgreSQL container
- [x] Flyway migrations run automatically in test context
- [x] Container is reused across tests in the same JVM (static initialization)

**Automated Tests:**
- [x] `TestContainerSmokeTest` — asserts the PostgreSQL container starts and accepts a connection

---

## Phase 1 — Database Migrations (Flyway)

**Objective:** Produce the complete, validated PostgreSQL schema via versioned Flyway migrations. All tables, indexes, constraints, and seed data must be in place before any entity or repository code is written.

**Dependencies:** Phase 0 complete.

**Complexity:** Medium

### Phase 1.1 — Flyway Baseline Configuration

**Implementation Tasks:**

- [x] Create `src/main/resources/db/migration/` directory
- [x] Confirm Flyway configuration: `locations = classpath:db/migration`, `validateOnMigrate = true`, `outOfOrder = false`
- [x] Configure `baselineOnMigrate: false` (fresh schema; no pre-existing data to baseline)
- [x] Ensure `spring.jpa.hibernate.ddl-auto=validate` — Hibernate must NOT manage schema

**Acceptance Criteria:**
- [x] Application startup fails if a migration file is tampered (checksum mismatch)
- [x] Flyway `flyway_schema_history` table is created automatically on first run

**Automated Tests:**
- [x] `FlywayMigrationTest` — `@SpringBootTest`, asserts `Flyway.info().applied().length` equals the expected migration count

---

### Phase 1.2 — V1: Lookup Tables Migration

**File:** `V1__create_lookup_tables.sql`

**Implementation Tasks:**

- [x] Create `account_statuses` table (id UUID PK, name, slug unique, description, is_active, created_at, updated_at)
- [x] Create `auth_origins` table
- [x] Create `oauth_providers` table
- [x] Create `lockout_types` table
- [x] Create `permission_categories` table
- [x] Create `authentication_methods` table
- [x] Create `audit_event_types` table (with category VARCHAR(50), severity VARCHAR(20))
- [x] Create `audit_outcomes` table
- [x] Add all unique indexes as defined in the schema document

**Acceptance Criteria:**
- [x] All 8 lookup tables created with correct column types and constraints
- [x] Unique indexes on all `slug` columns
- [x] `(category, severity)` composite index on `audit_event_types`
- [x] Migration applies cleanly on a fresh PostgreSQL 18 instance

**Automated Tests:**
- [x] `LookupTablesMigrationTest` — asserts all 8 tables exist via `DatabaseMetaData`

---

### Phase 1.3 — V2: Seed Data for Lookup Tables

**File:** `V2__seed_lookup_tables.sql`

**Implementation Tasks:**

- [x] Seed `account_statuses`: `ACTIVE`, `INACTIVE`, `LOCKED`, `PENDING_VERIFICATION`
- [x] Seed `auth_origins`: `LOCAL`, `GOOGLE`, `MIXED`
- [x] Seed `oauth_providers`: `GOOGLE`
- [x] Seed `lockout_types`: `AUTOMATIC`, `MANUAL`
- [x] Seed `permission_categories`: `USER_MANAGEMENT`, `ROLE_MANAGEMENT`, `PERMISSION_MANAGEMENT`, `AUDIT`, `AUTH_MANAGEMENT`
- [x] Seed `authentication_methods`: `PASSWORD`, `GOOGLE_OAUTH2`, `MFA_TOTP`
- [x] Seed `audit_event_types` with full taxonomy and severity mappings:
  - AUTHENTICATION category: `USER_REGISTERED` (NORMAL), `USER_REGISTERED_GOOGLE` (NORMAL), `ACCOUNT_LINKED_GOOGLE` (NORMAL), `AUTH_SUCCESS` (NORMAL), `AUTH_FAILURE` (HIGH), `AUTH_LOGOUT` (NORMAL), `EMAIL_VERIFIED` (NORMAL)
  - ACCOUNT category: `ACCOUNT_LOCKED` (HIGH), `ACCOUNT_UNLOCKED` (NORMAL), `USER_CREATED` (NORMAL), `USER_DISABLED` (HIGH), `USER_ACTIVATED` (NORMAL), `USER_DELETED` (HIGH), `PASSWORD_CHANGED` (HIGH), `PASSWORD_RESET_REQUESTED` (NORMAL), `PASSWORD_RESET_COMPLETED` (HIGH), `CONSENT_ACCEPTED` (NORMAL), `PROVIDER_UNLINKED` (NORMAL)
  - TOKEN category: `CREDENTIALS_INVALIDATED` (CRITICAL)
  - AUTHORIZATION category: `ROLE_ASSIGNED` (HIGH), `ROLE_REMOVED` (HIGH), `ROLE_CREATED` (NORMAL), `PERMISSION_GRANTED` (HIGH), `PERMISSION_REVOKED` (HIGH)
- [x] Seed `audit_outcomes`: `SUCCESS`, `FAILURE`

**Acceptance Criteria:**
- [x] All seed rows present with correct slugs and severities
- [x] All `is_active = true` on seed rows
- [x] Re-running migration (in test context) produces no duplicate key errors (use `INSERT ... ON CONFLICT DO NOTHING`)

**Automated Tests:**
- [x] `SeedDataTest` — asserts all expected slugs are present in each lookup table

---

### Phase 1.4 — V3: Core Identity Schema

**File:** `V3__create_core_identity.sql`

**Implementation Tasks:**

- [x] Create `users` table with all columns as per database schema:
  - `id UUID PK DEFAULT gen_random_uuid()`
  - `email VARCHAR(255) NOT NULL UNIQUE`
  - `password_hash VARCHAR(255)`
  - `display_name VARCHAR(100)`
  - `account_status_id UUID NOT NULL REFERENCES account_statuses(id)`
  - `auth_origin_id UUID NOT NULL REFERENCES auth_origins(id)`
  - `email_verified_at TIMESTAMPTZ`
  - `failed_login_attempts INT NOT NULL DEFAULT 0`
  - `lockout_expires_at TIMESTAMPTZ`
  - `lockout_type_id UUID REFERENCES lockout_types(id)`
  - `lockout_reason TEXT`
  - `last_login_at TIMESTAMPTZ`
  - `credentials_updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`
  - `consent_accepted_at TIMESTAMPTZ`
  - `consent_version VARCHAR(20)`
  - `deleted_at TIMESTAMPTZ`
  - `anonymized_at TIMESTAMPTZ`
  - `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`
  - `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`
- [x] Create all indexes defined in schema: `uidx_users_email`, `idx_users_account_status`, `idx_users_auth_origin`, `idx_users_email_deleted`, `idx_users_status_deleted`, `idx_users_last_login`, `idx_users_deleted_at`

**Acceptance Criteria:**
- [x] `users` table created with all FK constraints enforced at database level
- [x] Composite index `(email, deleted_at)` present for login-path performance
- [x] `credentials_updated_at` has `NOT NULL DEFAULT now()`

**Automated Tests:**
- [x] `UsersTableMigrationTest` — asserts table columns and NOT NULL constraints via `DatabaseMetaData`

---

### Phase 1.5 — V4: RBAC Schema

**File:** `V4__create_rbac.sql`

**Implementation Tasks:**

- [x] Create `roles` table with `is_system_role`, `is_active`, `created_by_id` FK to `users(id)`
- [x] Create `permissions` table with `category_id` FK to `permission_categories(id)`, `is_system_perm`, `is_active`
- [x] Create `role_permissions` join table — unique constraint on `(role_id, permission_id)`
- [x] Create `user_roles` join table — unique constraint on `(user_id, role_id)`, `expires_at` column
- [x] Create `user_permissions` join table — unique constraint on `(user_id, permission_id)`, `expires_at` column
- [x] Create all RBAC indexes as per schema document

**Acceptance Criteria:**
- [x] All unique constraints enforced at DB level (idempotent assignment support)
- [x] FK constraints to `users(id)` on `created_by_id` and `granted_by_id` columns
- [x] `role_permissions` and `user_permissions` have no cascade deletes on user FK (soft-delete safety)

**Automated Tests:**
- [x] `RbacSchemaMigrationTest` — asserts all 5 RBAC tables exist with unique constraints

---

### Phase 1.6 — V5: Token Management Schema

**File:** `V5__create_token_tables.sql`

**Implementation Tasks:**

- [x] Create `email_verification_tokens` table: `token_hash` unique, `new_email` nullable (for email-change flow), `expires_at`, `consumed_at`, `invalidated_at`
- [x] Create `password_reset_tokens` table: `token_hash` unique, `expires_at`, `consumed_at`, `invalidated_at`, `ip_address_masked`
- [x] Add composite indexes for active token lookup: `(user_id, consumed_at, invalidated_at)`
- [x] Add index on `expires_at` for cleanup pipeline queries

**Acceptance Criteria:**
- [x] `token_hash` has unique constraint — prevents token collision
- [x] `consumed_at` and `invalidated_at` columns are nullable (set on state transition)
- [x] No raw token values — schema enforces only hash storage by convention

**Automated Tests:**
- [x] `TokenTablesMigrationTest` — asserts indexes and unique constraints exist

---

### Phase 1.7 — V6: OAuth2, Brute Force, Audit & Privacy Schema

**File:** `V6__create_oauth_security_audit_privacy.sql`

**Implementation Tasks:**

- [x] Create `oauth_accounts` table: `(provider_id, provider_user_id)` unique, `unlinked_at` for soft-unlink, `last_used_at`
- [x] Create `login_attempts` table: `user_id` nullable FK, `auth_method_id`, `ip_address_masked`, `failure_context`, `correlation_id`, `was_successful`, `attempted_at`
- [x] Create `account_lockouts` table: `locked_by_id` nullable FK, `expires_at` nullable, `unlocked_at`, `unlocked_by_id`
- [x] Create `audit_logs` table: `event_type_id`, `outcome_id`, `actor_user_id`, `target_user_id`, `metadata JSONB`, `correlation_id` — NO `updated_at`
- [x] Create `user_consents` table: `consent_version`, `accepted_at`, `revoked_at`, `revocation_reason`
- [x] Create `mfa_configurations` table (inactive scaffold): `(user_id, method_id)` unique
- [x] Create all indexes as per schema document, especially:
  - `idx_audit_logs_target_time` on `(target_user_id, created_at)`
  - `idx_audit_logs_type_time` on `(event_type_id, created_at)`
  - `idx_login_attempts_ip_time` on `(ip_address_masked, attempted_at)`

**Acceptance Criteria:**
- [x] `audit_logs` has no `updated_at` (append-only enforced at schema level)
- [x] All FK constraints declared; no cascade deletes on user FKs in `audit_logs`
- [x] Composite indexes for time-range queries present

**Automated Tests:**
- [x] `AuditSchemaTest` — asserts `audit_logs` has no `updated_at` column
- [x] `LoginAttemptsSchemaTest` — asserts `user_id` is nullable (unknown-email attempts)

---

### Phase 1.8 — V7: RBAC Seed Data

**File:** `V7__seed_rbac_defaults.sql`

**Implementation Tasks:**

- [x] Seed system permissions (all with `is_system_perm = true`):
  - `user:create`, `user:read`, `user:update`, `user:delete`
  - `role:create`, `role:update`, `role:delete`
  - `permission:grant`, `permission:revoke`
  - `audit:view`
  - `auth:manage`
- [x] Seed system roles:
  - `ADMIN` (`is_system_role = true`) — assigned all system permissions
  - `USER` (`is_system_role = true`) — no permissions (baseline authenticated user)
- [x] Seed `role_permissions` linking `ADMIN` to all system permissions

**Acceptance Criteria:**
- [x] `ADMIN` role has all 11 system permissions via `role_permissions`
- [x] `USER` role has zero permissions
- [x] All seeds use `ON CONFLICT DO NOTHING` for idempotency
- [x] `is_system_perm = true` and `is_system_role = true` prevent API deletion

**Automated Tests:**
- [x] `RbacSeedTest` — asserts ADMIN role has 11 permissions; USER has 0

---

## Phase 2 — Domain Layer (JPA Entities)

**Objective:** Implement all JPA entities mapped to the Flyway-managed schema. Entities must validate against the schema via `ddl-auto: validate`.

**Dependencies:** Phase 1 complete (all migrations applied).

**Complexity:** Medium

### Phase 2.1 — Lookup Table Entities

**Implementation Tasks:**

- [x] Create `AccountStatus.java` entity: fields matching `account_statuses` table; no setters on `id`, `slug`
- [x] Create `AuthOrigin.java` entity
- [x] Create `OauthProvider.java` entity
- [x] Create `LockoutType.java` entity
- [x] Create `PermissionCategory.java` entity
- [x] Create `AuthenticationMethod.java` entity
- [x] Create `AuditEventType.java` entity (include `category`, `severity` fields)
- [x] Create `AuditOutcome.java` entity
- [x] All lookup entities: `@Table`, `@Column` annotations matching exact column names; Lombok `@Getter`, `@NoArgsConstructor`
- [x] Create `LookupEntityRepository` marker interface for common lookup queries

**Acceptance Criteria:**
- [x] `ddl-auto: validate` passes without schema drift errors for all lookup tables
- [x] Lombok-generated equals/hashCode based on `id` only (UUID PK)

**Automated Tests:**
- [x] `LookupEntityRepositoryTest` — `@DataJpaTest`, asserts `findBySlug()` returns seeded values for each lookup entity

---

### Phase 2.2 — Core Identity Entity

**Implementation Tasks:**

- [x] Create `User.java` entity:
  - `@Entity @Table(name = "users")`
  - All fields with exact `@Column` names
  - `@ManyToOne` to `AccountStatus`, `AuthOrigin`, `LockoutType`
  - `@CreationTimestamp` on `createdAt`, `@UpdateTimestamp` on `updatedAt`
  - `credentials_updated_at` as `Instant` — `@Column(nullable = false)`
  - `deletedAt` as nullable `Instant`
  - `@ToString.Exclude` on `passwordHash` — Lombok must never include it in toString
  - `@JsonIgnore` equivalent: `passwordHash` excluded from all serialization paths
  - No `@OneToMany` collection on `User` — avoid N+1; use separate repositories
- [x] Create `UserSlugConstants.java` — constants for account status slugs and auth origin slugs used in service layer comparisons

**Acceptance Criteria:**
- [x] `ddl-auto: validate` passes for `users` table
- [x] `passwordHash` field has NO getter accessible from outside `security` package (package-private getter pattern or service-layer-only access)
- [x] `toString()` output never includes `passwordHash`

**Automated Tests:**
- [x] `UserEntityTest` — asserts `toString()` does not contain `passwordHash`
- [x] `UserRepositoryTest` — `@DataJpaTest`, `findByEmailAndDeletedAtIsNull()`, `findById()`, optimistic locking test

---

### Phase 2.3 — RBAC Entities

**Implementation Tasks:**

- [x] Create `Role.java` entity: `@ManyToOne` to `User` for `createdById` (nullable)
- [x] Create `Permission.java` entity: `@ManyToOne` to `PermissionCategory`
- [x] Create `RolePermission.java` entity: composite business key `(roleId, permissionId)`, `grantedById` FK nullable
- [x] Create `UserRole.java` entity: composite business key `(userId, roleId)`, `expiresAt` nullable
- [x] Create `UserPermission.java` entity: composite business key `(userId, permissionId)`, `expiresAt` nullable

**Acceptance Criteria:**
- [x] Unique constraints on join tables enforced at entity level via `@Table(uniqueConstraints = ...)`
- [x] No cascade deletes from Role/Permission to join tables
- [x] `ddl-auto: validate` passes for all 5 RBAC tables

**Automated Tests:**
- [x] `RoleRepositoryTest` — asserts `findByNameIgnoreCase()`, duplicate name throws `DataIntegrityViolationException`
- [x] `UserRoleRepositoryTest` — asserts idempotent insert (duplicate throws `DataIntegrityViolationException`)

---

### Phase 2.4 — Token, OAuth2, Brute Force, Audit & Privacy Entities

**Implementation Tasks:**

- [x] Create `EmailVerificationToken.java` entity: `newEmail` nullable, `consumedAt` and `invalidatedAt` nullable `Instant`
- [x] Create `PasswordResetToken.java` entity: `ipAddressMasked`, `consumedAt`, `invalidatedAt`
- [x] Create `OauthAccount.java` entity: `@ManyToOne` to `User` and `OauthProvider`; `unlinkedAt` nullable
- [x] Create `LoginAttempt.java` entity: `userId` nullable UUID (not a FK-mapped relation), `@ManyToOne` to `AuthenticationMethod`; `failureContext` — internal field, `@JsonIgnore`
- [x] Create `AccountLockout.java` entity: `lockedById` FK nullable, `expiresAt` nullable
- [x] Create `AuditLog.java` entity: `metadata` as `Map<String, Object>` mapped with `@Type(JsonType.class)` or `@JdbcTypeCode(SqlTypes.JSON)`; NO `updatedAt`
- [x] Create `UserConsent.java` entity: `revokedAt`, `revocationReason` nullable
- [x] Create `MfaConfiguration.java` entity (inactive scaffold — no service wired)

**Acceptance Criteria:**
- [x] `AuditLog` entity has no `updatedAt` field — enforces append-only
- [x] `LoginAttempt.userId` is a plain `UUID` field (not `@ManyToOne`) — supports null for unknown-email attempts
- [x] `ddl-auto: validate` passes for all tables after all 8 entity classes are present

**Automated Tests:**
- [x] `AuditLogRepositoryTest` — asserts `findByTargetUserIdOrderByCreatedAtDesc(UUID)` returns correct ordering
- [x] `PasswordResetTokenRepositoryTest` — asserts `findByTokenHashAndConsumedAtIsNullAndInvalidatedAtIsNull()` query

---

### Phase 2.5 — Domain Exceptions

**Implementation Tasks:**

- [x] Create `AuthException.java` — base unchecked exception
- [x] Create `InvalidCredentialsException.java extends AuthException` — generic auth failure (anti-enumeration)
- [x] Create `AccountLockedException.java extends AuthException`
- [x] Create `AccountNotVerifiedException.java extends AuthException`
- [x] Create `AccountDisabledException.java extends AuthException`
- [x] Create `AccountDeletedException.java extends AuthException`
- [x] Create `TokenExpiredException.java extends AuthException`
- [x] Create `TokenAlreadyConsumedException.java extends AuthException`
- [x] Create `EmailAlreadyExistsException.java extends AuthException` — internal use only; never returned raw to client
- [x] Create `ResourceNotFoundException.java extends AuthException`
- [x] Create `ConflictException.java extends AuthException`
- [x] Create `PermissionDeniedException.java extends AuthException` — thrown when `@PreAuthorize` not sufficient
- [x] Create `OAuthProviderException.java extends AuthException`

**Acceptance Criteria:**
- [x] All exceptions are unchecked (`extends RuntimeException` chain)
- [x] No exception exposes a user-existence hint in its message by default
- [x] All exceptions include a `correlationId` field populated at throw site

**Automated Tests:**
- [x] `DomainExceptionTest` — unit tests asserting exception hierarchy and message formatting

---

## Phase 3 — Repository Layer

**Objective:** Implement Spring Data JPA repositories with all query methods required by the service layer. No business logic in repositories.

**Dependencies:** Phase 2 complete.

**Complexity:** Low

### Phase 3.1 — Core Repositories

**Implementation Tasks:**

- [x] `UserRepository extends JpaRepository<User, UUID>`:
  - `Optional<User> findByEmailAndDeletedAtIsNull(String email)`
  - `Optional<User> findByIdAndDeletedAtIsNull(UUID id)`
  - `boolean existsByEmailAndDeletedAtIsNull(String email)`
  - `Page<User> findAllByDeletedAtIsNull(Pageable pageable)`
  - `Page<User> findAllByDeletedAtIsNullAndAccountStatusId(UUID statusId, Pageable pageable)`
- [x] `RoleRepository`: `Optional<Role> findByNameIgnoreCase(String name)`, `boolean existsByName(String name)`
- [x] `PermissionRepository`: `Optional<Permission> findByName(String name)`, `List<Permission> findByCategoryId(UUID categoryId)`
- [x] `RolePermissionRepository`: `List<RolePermission> findByRoleId(UUID roleId)`, `boolean existsByRoleIdAndPermissionId(UUID, UUID)`, `deleteByRoleIdAndPermissionId(UUID, UUID)`
- [x] `UserRoleRepository`: `List<UserRole> findByUserId(UUID userId)`, `boolean existsByUserIdAndRoleId(UUID, UUID)`, `deleteByUserIdAndRoleId(UUID, UUID)`
- [x] `UserPermissionRepository`: `List<UserPermission> findByUserId(UUID userId)`, `boolean existsByUserIdAndPermissionId(UUID, UUID)`, `deleteByUserIdAndPermissionId(UUID, UUID)`

**Acceptance Criteria:**
- [x] All query methods have derived query names or explicit `@Query` JPQL — no native queries unless unavoidable
- [x] No business logic or conditional branching in any repository interface or implementation

**Automated Tests:**
- [x] `UserRepositoryTest` (`@DataJpaTest`) — tests `findByEmailAndDeletedAtIsNull`, soft-delete filter, pagination
- [x] `RolePermissionRepositoryTest` — idempotent insert test, delete test

---

### Phase 3.2 — Token & Security Repositories

**Implementation Tasks:**

- [x] `EmailVerificationTokenRepository`:
  - `Optional<EmailVerificationToken> findByTokenHashAndConsumedAtIsNullAndInvalidatedAtIsNull(String hash)`
  - `List<EmailVerificationToken> findByUserIdAndConsumedAtIsNullAndInvalidatedAtIsNull(UUID userId)`
  - `int invalidateActiveTokensForUser(UUID userId)` — `@Modifying @Query` update
- [x] `PasswordResetTokenRepository`:
  - `Optional<PasswordResetToken> findByTokenHashAndConsumedAtIsNullAndInvalidatedAtIsNull(String hash)`
  - `int invalidateActiveTokensForUser(UUID userId)` — `@Modifying @Query`
  - `deleteByExpiresAtBeforeAndConsumedAtIsNotNull(Instant cutoff)` — cleanup
- [x] `OauthAccountRepository`:
  - `Optional<OauthAccount> findByProviderIdAndProviderUserIdAndUnlinkedAtIsNull(UUID, String)`
  - `Optional<OauthAccount> findByUserIdAndProviderIdAndUnlinkedAtIsNull(UUID, UUID)`
- [x] `LoginAttemptRepository`:
  - `int countByUserIdAndWasSuccessfulFalseAndAttemptedAtAfter(UUID userId, Instant since)`
  - `int countByIpAddressMaskedAndAttemptedAtAfter(String ip, Instant since)`
- [x] `AccountLockoutRepository`:
  - `Optional<AccountLockout> findByUserIdAndUnlockedAtIsNull(UUID userId)`
- [x] `AuditLogRepository`:
  - `Page<AuditLog> findByTargetUserIdOrderByCreatedAtDesc(UUID, Pageable)`
  - `Page<AuditLog> findByEventTypeIdAndCreatedAtBetween(UUID, Instant, Instant, Pageable)`
  - `Page<AuditLog> findByActorUserIdAndCreatedAtBetween(UUID, Instant, Instant, Pageable)`
- [x] `UserConsentRepository`: `Optional<UserConsent> findTopByUserIdAndRevokedAtIsNullOrderByAcceptedAtDesc(UUID)`
- [x] Lookup repositories: `AccountStatusRepository`, `AuthOriginRepository`, `AuditEventTypeRepository`, `AuditOutcomeRepository`, `LockoutTypeRepository`, `AuthenticationMethodRepository`, `OauthProviderRepository` — all with `findBySlug(String slug)`

**Acceptance Criteria:**
- [x] Token lookup queries filter by `consumedAt IS NULL AND invalidatedAt IS NULL` — active tokens only
- [x] Modifying queries use `@Transactional` and `@Modifying`
- [x] All lookup repositories cache slugs via `@Cacheable` or a singleton bean to avoid repeated DB reads

**Automated Tests:**
- [x] `PasswordResetTokenRepositoryTest` — consumed token not returned, invalidated token not returned
- [x] `AuditLogRepositoryTest` — pagination, ordering, user-scoped query
- [x] `LoginAttemptRepositoryTest` — count query returns correct failed attempt count within time window

---

## Phase 4 — Security Infrastructure

**Objective:** Implement the full Spring Security configuration: JWT issuance and validation, stateless authentication filter, custom `UserDetailsService`, HTTP security headers, CORS, CSRF disabled, and method-level security.

**Dependencies:** Phase 2 and 3 complete.

**Complexity:** High

### Phase 4.1 — Password Hashing (Argon2id)

**Implementation Tasks:**

- [x] Create `PasswordEncoderConfig.java` `@Configuration`:
  - Expose `PasswordEncoder` bean using `Argon2PasswordEncoder` (Spring Security built-in, wraps Bouncy Castle)
  - Parameters: `saltLength=16`, `hashLength=32`, `parallelism=1`, `memory=65536` (64 MB), `iterations=3` (OWASP recommendation)
- [x] Create `PasswordPolicy.java` — validates password strength:
  - Minimum 12 characters
  - At least 1 uppercase, 1 lowercase, 1 digit, 1 special character
  - Implemented as Jakarta `@ConstraintValidator`
- [x] Create `@ValidPassword` annotation linking to `PasswordPolicy`

**Acceptance Criteria:**
- [x] `PasswordEncoder.encode()` produces Argon2id-prefixed hash (`$argon2id$...`)
- [x] `PasswordEncoder.matches()` returns true for correct password, false for wrong
- [x] Raw password is never accessible after encoding

**Automated Tests:**
- [x] `PasswordEncoderTest` — encodes and matches; asserts hash prefix; asserts different salts produce different hashes
- [x] `PasswordPolicyTest` — unit tests covering all strength rules including boundary cases

---

### Phase 4.2 — JWT Service

**Implementation Tasks:**

- [x] Create `JwtService.java` (interface) + `JwtServiceImpl.java`:
  - `String generateToken(UUID userId, List<String> authorities, Instant credentialsUpdatedAt)` — issues signed JWT
  - `Claims validateAndParseClaims(String token)` — validates signature + expiry; throws `TokenExpiredException` or `AuthException`
  - `UUID extractUserId(Claims claims)`
  - `List<String> extractAuthorities(Claims claims)`
  - `Instant extractIssuedAt(Claims claims)`
- [x] JWT claims structure:
  - `sub`: `userId.toString()` (UUID — not email)
  - `authorities`: `List<String>` of `resource:action` strings
  - `token_type`: `"access"`
  - `iat`: current timestamp
  - `exp`: `iat + appProperties.jwt.expirationMinutes`
- [x] Sign with `HS512` using secret from `AppProperties` (base64-decoded to byte[])
- [x] JWT secret minimum length enforcement: fail fast if secret < 64 characters
- [x] Token size guard: log a warning if JWT exceeds 4 KB (many permission sets)
- [x] `credentials_updated_at` is NOT stored in the JWT — validation requires a DB read at filter time

**Acceptance Criteria:**
- [x] `sub` claim contains UUID string — never email or other PII
- [x] `authorities` claim is a JSON array of strings
- [x] Expired token throws `TokenExpiredException` with clear internal message
- [x] Invalid signature throws `AuthException`
- [x] Secret shorter than 64 chars causes application startup failure

**Automated Tests:**
- [x] `JwtServiceTest`:
  - Token generated with correct `sub`, `authorities`, `exp`
  - Expired token (artificially back-dated) throws `TokenExpiredException`
  - Tampered signature throws `AuthException`
  - `sub` claim never contains `@` (not email)
  - `authorities` claim contains no PII fields

---

### Phase 4.3 — JWT Authentication Filter

**Implementation Tasks:**

- [x] Create `JwtAuthenticationFilter.java extends OncePerRequestFilter`:
  - Extract `Authorization: Bearer <token>` header
  - If absent or malformed: `filterChain.doFilter()` without authentication (let Spring Security 403/401 handle)
  - Parse and validate JWT via `JwtService`
  - Extract `userId` from claims
  - Perform lightweight DB read: `userRepository.findByIdAndDeletedAtIsNull(userId)` to get `credentials_updated_at` and account status
  - Validate `jwt.iat >= user.credentialsUpdatedAt` — reject with 401 if stale
  - Validate user status is `ACTIVE` — reject with 401 if not
  - Build `UsernamePasswordAuthenticationToken` with granted authorities from JWT claims
  - Set authentication in `SecurityContextHolder`
  - Add `correlationId` to MDC
- [x] The filter must NOT produce `AUTH_FAILURE` audit events for expired tokens — only for intentional auth failures
- [x] Create `CorrelationIdFilter.java extends OncePerRequestFilter` — generates UUID per request, sets MDC key `correlationId`, adds `X-Correlation-Id` response header

**Acceptance Criteria:**
- [x] Authenticated request sets `SecurityContextHolder` with correct authorities
- [x] Stale JWT (issued before `credentials_updated_at`) returns 401
- [x] Disabled/deleted/locked user's JWT returns 401 even if token is cryptographically valid
- [x] Missing `Authorization` header passes through to Spring Security's anonymous filter
- [x] DB read uses indexed PK — single `SELECT` per authenticated request

**Automated Tests:**
- [x] `JwtAuthenticationFilterTest` (`@WebMvcTest` with mocked deps):
  - Valid token → 200 on protected endpoint
  - Expired token → 401
  - Stale token (iat < credentials_updated_at) → 401
  - Missing header → 401 on protected endpoint
  - Disabled user token → 401
  - Soft-deleted user token → 401

---

### Phase 4.4 — Custom UserDetailsService

**Implementation Tasks:**

- [x] Create `AuthUserDetailsService.java implements UserDetailsService`:
  - `loadUserByUsername(String email)` — used during form-login (not primary flow, but required by Spring Security)
  - Returns `UserDetails` wrapping `User` entity with authorities derived from DB RBAC state
  - Used only during initial credential verification in `AuthService`; the JWT filter bypasses this after issuance
- [x] Create `AuthenticatedUser.java implements UserDetails`:
  - Wraps `User` entity
  - `getAuthorities()` — returns `GrantedAuthority` list from `authorities` claim (set externally during JWT validation)
  - `isAccountNonLocked()` checks `user.accountStatus.slug` and `user.lockoutExpiresAt`
  - `isEnabled()` checks `user.deletedAt IS NULL` and status is ACTIVE
  - Overrides `getPassword()` to return `passwordHash` — package-private, never serialized

**Acceptance Criteria:**
- [x] `isAccountNonLocked()` returns false for locked or disabled users
- [x] `isEnabled()` returns false for soft-deleted users
- [x] `getAuthorities()` returns authorities derived from RBAC join tables (not JWT) during login validation
- [x] `toString()` does not expose `passwordHash`

**Automated Tests:**
- [x] `AuthUserDetailsServiceTest` — unit tests for each `isAccountNon*` method for all status transitions

---

### Phase 4.5 — Spring Security Filter Chain Configuration

**Implementation Tasks:**

- [x] Create `SecurityConfig.java @Configuration @EnableWebSecurity @EnableMethodSecurity`:
  - Disable CSRF (stateless JWT API)
  - Disable HTTP Basic
  - Disable form login
  - Disable session creation (`SessionCreationPolicy.STATELESS`)
  - Configure `AuthenticationEntryPoint` → returns RFC 7807 `401` JSON (no redirect)
  - Configure `AccessDeniedHandler` → returns RFC 7807 `403` JSON (no redirect, no detail leak)
  - Add `CorrelationIdFilter` before `UsernamePasswordAuthenticationFilter`
  - Add `JwtAuthenticationFilter` before `UsernamePasswordAuthenticationFilter`
  - Configure public endpoints (no auth required):
    - `POST /api/v1/auth/register`
    - `POST /api/v1/auth/login`
    - `POST /api/v1/auth/password-reset/request`
    - `POST /api/v1/auth/password-reset/confirm`
    - `GET /api/v1/auth/email/verify`
    - `POST /api/v1/auth/email/verify/resend`
    - `GET /oauth2/**`
    - `GET /login/oauth2/**`
    - `GET /actuator/health`
    - `GET /swagger-ui/**`, `GET /v3/api-docs/**`
  - All other endpoints: `authenticated()`
  - Configure `AuthenticationManager` bean for use in `AuthService`
- [x] Create `AuthenticationEntryPoint` implementation:
  - Returns `{ "errorCode": "UNAUTHORIZED", "message": "Authentication required.", "correlationId": "..." }`
  - Status `401`, `Content-Type: application/json`
  - `Cache-Control: no-store`
- [x] Create `CustomAccessDeniedHandler` implementation:
  - Returns `{ "errorCode": "FORBIDDEN", "message": "Access denied.", "correlationId": "..." }`
  - Status `403`; never names the required permission

**Acceptance Criteria:**
- [x] Unauthenticated request to protected endpoint returns `401` JSON (not a redirect)
- [x] Authenticated request without required permission returns `403` JSON (not a redirect)
- [x] `SessionCreationPolicy.STATELESS` — no `JSESSIONID` cookie ever set
- [x] CSRF disabled — `POST` requests without CSRF tokens are accepted
- [x] `@EnableMethodSecurity` active — `@PreAuthorize` works on service methods

**Automated Tests:**
- [x] `SecurityConfigTest` (`@SpringBootTest`):
  - Public endpoints return non-401 without token
  - Protected endpoints return 401 without token
  - 401 response body is JSON with `correlationId`
  - 403 response body is JSON without permission name
  - No `Set-Cookie` header on any auth response

---

### Phase 4.6 — HTTP Security Headers & CORS Configuration

**Implementation Tasks:**

- [x] In `SecurityConfig`, configure headers:
  - `X-Content-Type-Options: nosniff`
  - `X-Frame-Options: DENY`
  - `Referrer-Policy: strict-origin-when-cross-origin`
  - `Cache-Control: no-store` on auth endpoints
  - `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`
  - `Strict-Transport-Security: max-age=31536000; includeSubDomains` (production profile only)
- [x] Configure CORS via `CorsConfigurationSource` bean:
  - `allowedOrigins`: driven by `${ALLOWED_ORIGINS}` env var (no `*` in production)
  - `allowedMethods`: `GET, POST, PUT, DELETE, OPTIONS`
  - `allowedHeaders`: `Authorization, Content-Type, X-Correlation-Id`
  - `allowCredentials: false` (stateless JWT — no cookies)
  - `maxAge: 3600`

**Acceptance Criteria:**
- [x] `X-Content-Type-Options: nosniff` present on all responses
- [x] `X-Frame-Options: DENY` present on all responses
- [x] CORS pre-flight `OPTIONS` returns correct headers
- [x] CORS with `Origin: *` is rejected in production profile

**Automated Tests:**
- [x] `SecurityHeadersTest` — MockMvc assertions on all required headers
- [x] `CorsConfigTest` — pre-flight test, wildcard origin rejection test

---

### Phase 4.7 — Permission Resolution & Authority Building

**Implementation Tasks:**

- [x] Create `PermissionResolver.java`:
  - `List<String> resolveEffectivePermissions(UUID userId)` — fetches role permissions + direct user permissions from DB, returns deduplicated sorted list
  - Used at JWT issuance time only; not called on each request
- [x] Create `AuthorityMapper.java`:
  - `List<GrantedAuthority> fromPermissionList(List<String> permissions)` — maps `"user:create"` → `SimpleGrantedAuthority("user:create")`
  - Used in both JWT filter (from JWT claims) and `UserDetails` (from DB at login)

**Acceptance Criteria:**
- [x] Effective permissions = union of all role permissions and direct user permissions
- [x] Duplicate permissions collapsed to single authority
- [x] Direct user permissions override/supplement role permissions (union, not conflict)

**Automated Tests:**
- [x] `PermissionResolverTest` — user with 2 roles and 1 direct permission; asserts correct union with no duplicates
- [x] `AuthorityMapperTest` — maps permission strings to `GrantedAuthority` list correctly

---

## Phase 5 — Cross-Cutting Utilities

**Objective:** Implement shared utilities required by multiple services: data masking, audit metadata sanitization, correlation ID management, and email infrastructure.

**Dependencies:** Phase 2 complete.

**Complexity:** Low

### Phase 5.1 — Data Masking Utilities

**Implementation Tasks:**

- [x] Create `DataMasker.java` (static utility or Spring bean):
  - `maskEmail(String email)` → `a***@example.com` pattern; handles edge cases (short local part)
  - `maskIpV4(String ip)` → last octet zeroed: `192.168.1.0`
  - `maskIpV6(String ip)` → last 80 bits zeroed
  - `maskIp(String ip)` → auto-detect IPv4 vs IPv6
  - `truncateUserAgent(String ua, int maxLength)` → truncates to configurable max (default 512)
  - `sanitizeTokenValue(String token)` → always returns `"[REDACTED]"`
- [x] Create `AuditMetadataSanitizer.java`:
  - `Map<String, Object> sanitize(Map<String, Object> metadata)` — removes keys matching a blocklist (`password`, `token`, `secret`, `hash`, `credential`)
  - Applies `maskEmail()` to any value that looks like an email (regex check)
  - Used by `AuditService` before every `AuditLog` persistence

**Acceptance Criteria:**
- [x] `maskEmail("user@example.com")` → `"u***@example.com"`
- [x] `maskEmail("ab@x.co")` → `"a***@x.co"` (short local part handled)
- [x] `maskIpV4("192.168.1.100")` → `"192.168.1.0"`
- [x] `sanitize({"password": "secret"})` → `{"password": "[REDACTED]"}`
- [x] All masking functions are null-safe (return `null` or `"[REDACTED]"` on null input)

**Automated Tests:**
- [x] `DataMaskerTest` — exhaustive unit tests for each masking method including edge cases
- [x] `AuditMetadataSanitizerTest` — sanitize with mixed safe/unsafe keys; email detection; nested map handling

---

### Phase 5.2 — Correlation ID & MDC Propagation

**Implementation Tasks:**

- [x] Create `CorrelationIdHolder.java` — `ThreadLocal<UUID>` wrapper with `get()`, `set()`, `clear()`
- [x] `CorrelationIdFilter` (defined in Phase 4.3) populates `CorrelationIdHolder` and MDC key `correlationId`
- [x] Ensure MDC is cleared after each request (in `finally` block in filter)
- [x] All exceptions thrown in service layer carry `correlationId` from `CorrelationIdHolder`
- [x] All `AuditLog` records include `correlationId` from `CorrelationIdHolder`

**Acceptance Criteria:**
- [x] Every request has a unique `correlationId` in MDC
- [x] `X-Correlation-Id` response header matches the MDC value
- [x] MDC is cleared after each request (no bleed between requests in thread pool)

**Automated Tests:**
- [x] `CorrelationIdFilterTest` — asserts response header present, value is valid UUID, MDC cleared after request

---

### Phase 5.3 — Email Service

**Implementation Tasks:**

- [x] Create `EmailService.java` (interface) + `SmtpEmailService.java` (implementation):
  - `sendEmailVerification(String toEmail, String verificationToken, String displayName)`
  - `sendPasswordResetEmail(String toEmail, String resetToken, String displayName)`
  - `sendAccountAlreadyExistsEmail(String toEmail)` — anti-enumeration
  - `sendEmailChangeVerification(String newEmail, String verificationToken)`
- [x] Use `JavaMailSender` configured via Spring Boot Mail auto-configuration
- [x] Email templates: simple text-based or Thymeleaf (configurable); include expiry time in body
- [x] Links in emails: `${APP_BASE_URL}/auth/verify-email?token=<raw-token>`
- [x] Raw token is passed to email service; token is never logged
- [x] Create `NoOpEmailService.java` — test/dev profile implementation that logs (masked) email events to console

**Acceptance Criteria:**
- [x] `SmtpEmailService` sends correctly formatted emails via JavaMail
- [x] `NoOpEmailService` used in test profile — no real SMTP connections in tests
- [x] Raw token never appears in any log line
- [x] Email addresses in log lines are masked via `DataMasker`

**Automated Tests:**
- [x] `EmailServiceTest` — unit test with `JavaMailSender` mock; asserts `MimeMessage` recipient and no token in subject
- [x] Integration: `NoOpEmailService` captures sent emails in-memory for assertion in auth flow tests

---

## Phase 6 — Service Layer

**Objective:** Implement all business logic services. Each service encapsulates a domain. Services depend on repository interfaces, not concrete classes. All audit events are recorded through `AuditService`.

**Dependencies:** Phases 3, 4, 5 complete.

**Complexity:** High

### Phase 6.1 — Audit Service

**Implementation Tasks:**

- [x] Create `AuditService.java` (interface) + `AuditServiceImpl.java`:
  - `record(AuditEventSlug eventSlug, AuditOutcomeSlug outcome, UUID actorId, UUID targetId, Map<String, Object> metadata)`
  - Resolves `AuditEventType` and `AuditOutcome` entities by slug (cached lookup)
  - Runs `AuditMetadataSanitizer.sanitize()` on metadata before persistence
  - Populates `ipAddressMasked`, `userAgentTruncated`, `correlationId` from request context
  - Saves `AuditLog` via `AuditLogRepository`
  - Method is `@Async` — audit persistence does not block the main request thread
  - Errors in audit persistence are caught and logged (never propagate to caller)
- [x] Create `AuditEventSlug.java` enum — constants matching seeded `audit_event_types.slug` values
- [x] Create `AuditOutcomeSlug.java` enum — `SUCCESS`, `FAILURE`
- [x] Create `RequestContext.java` — `ThreadLocal` holder for IP and User-Agent, populated by `CorrelationIdFilter`

**Acceptance Criteria:**
- [x] Every auditable event produces exactly one `AuditLog` row
- [x] Audit records never contain passwords, raw tokens, or unmasked emails
- [x] Audit persistence failure does not cause the main request to fail
- [x] `@Async` requires `@EnableAsync` in configuration

**Automated Tests:**
- [x] `AuditServiceTest` — asserts `AuditLog` saved with correct fields; metadata sanitized; IP masked
- [x] `AuditServiceAsyncTest` — asserts audit failure does not propagate exception

---

### Phase 6.2 — Authentication Service

**Implementation Tasks:**

- [x] Create `AuthService.java` (interface) + `AuthServiceImpl.java`:
  - `AuthResponse register(RegisterRequest request)`:
    1. Validate email format and password strength (via DTO `@Valid`)
    2. Check email uniqueness: if exists, dispatch `sendAccountAlreadyExistsEmail()` and return same success response (anti-enumeration)
    3. Hash password with `PasswordEncoder`
    4. Resolve `PENDING_VERIFICATION` account status and `LOCAL` auth origin from lookup repos
    5. Create and save `User` entity with `credentialsUpdatedAt = now()`
    6. Record `CONSENT_ACCEPTED` audit event; save `UserConsent` record
    7. Generate email verification token (UUID), hash it, save `EmailVerificationToken`
    8. Dispatch verification email with raw token
    9. Record `USER_REGISTERED` audit event
    10. Return `{ "message": "Registration successful. Please verify your email." }`
  - `AuthResponse login(LoginRequest request)`:
    1. Find user by email: if not found, increment counter for unknown email (IP-based) and throw `InvalidCredentialsException`
    2. Check `deletedAt IS NULL` — throw `InvalidCredentialsException` (anti-enumeration)
    3. Check account status via `AccountStatusChecker`
    4. Check `lockoutExpiresAt` — if still locked, record `AUTH_FAILURE`, throw `InvalidCredentialsException`
    5. Verify password with `PasswordEncoder.matches()`
    6. On failure: `incrementFailedAttempts(user)` → if threshold exceeded: `lockAccount(user)` → record `ACCOUNT_LOCKED`
    7. On success: reset failed attempts, update `lastLoginAt`, save `LoginAttempt(wasSuccessful=true)`
    8. Resolve effective permissions via `PermissionResolver`
    9. Issue JWT via `JwtService.generateToken(userId, authorities, credentialsUpdatedAt)`
    10. Record `AUTH_SUCCESS` audit event
    11. Return JWT in response body with `Cache-Control: no-store`
  - `void logout(UUID userId)` — record `AUTH_LOGOUT` audit event only
  - `void changePassword(UUID userId, ChangePasswordRequest request)`:
    1. Verify current password
    2. Validate new password strength; ensure differs from current
    3. Hash and save new password
    4. Update `credentialsUpdatedAt` to `now()`
    5. Record `PASSWORD_CHANGED` and `CREDENTIALS_INVALIDATED` audit events
- [x] Create `AccountStatusChecker.java` — extracts account status validation logic:
  - `checkAuthenticationEligibility(User user)` — checks status, deleted_at, verified email, lockout

**Acceptance Criteria:**
- [x] Login with non-existent email, wrong password, locked account, or unverified account all return identical `401` via `InvalidCredentialsException`
- [x] Successful login returns JWT with `Cache-Control: no-store` header
- [x] Password never logged at any log level
- [x] `credentialsUpdatedAt` updated on password change (all previous JWTs invalidated)

**Automated Tests:**
- [x] `AuthServiceTest` (unit, mocked repos):
  - Registration creates user in `PENDING_VERIFICATION`
  - Login with wrong password throws `InvalidCredentialsException`
  - Login increments failed attempts
  - Lockout triggered at threshold
  - Successful login resets failed attempts
  - Logout records audit event; does not modify server state
  - Password change updates `credentialsUpdatedAt`
- [x] `AuthIntegrationTest` (`@SpringBootTest`, Testcontainers):
  - Full register → verify → login → logout flow
  - Failed login 5x → account locked → 6th attempt returns same 401

---

### Phase 6.3 — Password Reset Service

**Implementation Tasks:**

- [x] Create `PasswordResetService.java` (interface) + `PasswordResetServiceImpl.java`:
  - `void initiateReset(PasswordResetRequest request)`:
    1. Find user by email
    2. If user exists and is active (not deleted, not disabled): invalidate all active reset tokens for user, generate secure token (UUID v4 or `SecureRandom` 256-bit hex), hash it, save `PasswordResetToken` with `expiresAt = now() + TTL`
    3. Dispatch `sendPasswordResetEmail()` with raw token
    4. **Always** return HTTP 200 (anti-enumeration — no distinction on existence)
    5. Record `PASSWORD_RESET_REQUESTED` audit event with masked email
  - `void completeReset(PasswordResetCompleteRequest request)`:
    1. Hash incoming token; look up active, non-expired token by hash
    2. If not found or expired: throw `TokenExpiredException` with generic message
    3. Validate new password strength
    4. Hash new password
    5. Mark token as consumed (`consumedAt = now()`)
    6. Update user: `passwordHash`, `credentialsUpdatedAt = now()`
    7. Record `PASSWORD_RESET_COMPLETED` and `CREDENTIALS_INVALIDATED` audit events
    8. Return success

**Acceptance Criteria:**
- [x] `initiateReset()` returns 200 regardless of whether email exists
- [x] `completeReset()` with expired token returns 400 with generic message
- [x] Token stored as hash — never plaintext
- [x] `credentialsUpdatedAt` updated on completion — all prior JWTs invalidated

**Automated Tests:**
- [x] `PasswordResetServiceTest` (unit):
  - Valid flow: token consumed, password updated, credentialsUpdatedAt bumped
  - Expired token throws `TokenExpiredException`
  - Consumed token throws `TokenAlreadyConsumedException`
  - Non-existent email produces no error, returns void
- [x] `PasswordResetIntegrationTest`:
  - Full flow: register → verify → reset request → use token → login with new password → old token rejected

---

### Phase 6.4 — Email Verification Service

**Implementation Tasks:**

- [x] Create `EmailVerificationService.java`:
  - `void verifyEmail(String rawToken)`:
    1. Hash incoming token; look up active, non-expired, non-consumed token by hash
    2. If token is for email change (`newEmail IS NOT NULL`): update `user.email = newEmail`, optionally update `credentialsUpdatedAt`
    3. If initial verification: update `user.accountStatus` to `ACTIVE`, set `user.emailVerifiedAt = now()`
    4. Mark token consumed
    5. Record `EMAIL_VERIFIED` audit event
  - `void resendVerification(ResendVerificationRequest request)`:
    1. Find user by email
    2. If not found or already verified: return success (anti-enumeration)
    3. Invalidate existing active tokens
    4. Generate new token, save, dispatch email
    5. Record audit event
  - `void initiateEmailChange(UUID userId, EmailChangeRequest request)`:
    1. Validate new email format
    2. If new email already exists: return uniform success (anti-enumeration)
    3. Generate token with `newEmail` set
    4. Dispatch verification to new email address

**Acceptance Criteria:**
- [x] Idempotent: verifying an already-verified token returns success (no error)
- [x] Expired verification token returns generic error
- [x] Email not activated until token consumed
- [x] Anti-enumeration on resend and email change flows

**Automated Tests:**
- [x] `EmailVerificationServiceTest`:
  - Valid flow activates account
  - Expired token rejected
  - Resend invalidates previous token
  - Already-verified account: resend returns success with no new token
- [x] `EmailVerificationIntegrationTest` — full flow with DB state assertions

---

### Phase 6.5 — User Management Service

**Implementation Tasks:**

- [x] Create `UserService.java`:
  - `UserProfileResponse getOwnProfile(UUID userId)` — returns masked DTO; never returns passwordHash
  - `UserAdminResponse getUserById(UUID userId)` — admin view; includes lockout status, failed attempts
  - `Page<UserSummaryResponse> listUsers(UserFilterRequest filter, Pageable pageable)` — soft-deleted excluded by default
  - `UserProfileResponse updateOwnProfile(UUID userId, UpdateProfileRequest request)` — non-credential fields only
  - `void disableUser(UUID actorId, UUID targetUserId, String reason)` — sets INACTIVE status, updates `credentialsUpdatedAt`, records audit
  - `void activateUser(UUID actorId, UUID targetUserId)` — sets ACTIVE status, records audit
  - `void softDeleteUser(UUID actorId, UUID targetUserId)` — sets `deletedAt`, updates `credentialsUpdatedAt`, records audit
  - `void adminCreateUser(UUID actorId, AdminCreateUserRequest request)` — creates user directly, dispatches set-password email

**Acceptance Criteria:**
- [x] `getOwnProfile` never returns `passwordHash`, token values, or full IP
- [x] `disableUser` updates `credentialsUpdatedAt` — all JWTs invalidated immediately
- [x] Soft-deleted users excluded from `listUsers` by default
- [x] `updateOwnProfile` does not allow password or email changes (those go through dedicated flows)

**Automated Tests:**
- [x] `UserServiceTest` (unit, mocked):
  - `disableUser` updates `credentialsUpdatedAt` and records both `USER_DISABLED` and `CREDENTIALS_INVALIDATED`
  - `softDeleteUser` sets `deletedAt` and updates `credentialsUpdatedAt`
  - `getOwnProfile` DTO contains no `passwordHash`
- [x] `UserManagementIntegrationTest`:
  - Disable user → their JWT is rejected on next request
  - Soft-delete → user excluded from list, cannot authenticate

---

### Phase 6.6 — RBAC Service

**Implementation Tasks:**

- [x] Create `RoleService.java`:
  - `RoleResponse createRole(UUID actorId, CreateRoleRequest request)` — unique name check, save, record `ROLE_CREATED` audit
  - `RoleResponse updateRole(UUID actorId, UUID roleId, UpdateRoleRequest request)` — description only (name immutable)
  - `void deleteRole(UUID actorId, UUID roleId)` — blocked if any `user_roles` row references this role (HTTP 409)
  - `Page<RoleResponse> listRoles(Pageable pageable)`
  - `RoleResponse getRoleById(UUID roleId)`
- [x] Create `PermissionService.java`:
  - `PermissionResponse createPermission(UUID actorId, CreatePermissionRequest request)` — unique name check
  - `void deletePermission(UUID actorId, UUID permissionId)` — blocked if assigned to any role or user (HTTP 409)
  - `Page<PermissionResponse> listPermissions(Pageable pageable)`
- [x] Create `RbacAssignmentService.java`:
  - `void assignPermissionToRole(UUID actorId, UUID roleId, UUID permissionId)` — idempotent, record `PERMISSION_GRANTED`
  - `void revokePermissionFromRole(UUID actorId, UUID roleId, UUID permissionId)` — record `PERMISSION_REVOKED`
  - `void assignRoleToUser(UUID actorId, UUID userId, UUID roleId)` — idempotent, record `ROLE_ASSIGNED`
  - `void revokeRoleFromUser(UUID actorId, UUID userId, UUID roleId)` — record `ROLE_REMOVED`
  - `void assignPermissionToUser(UUID actorId, UUID userId, UUID permissionId)` — idempotent, record `PERMISSION_GRANTED`
  - `void revokePermissionFromUser(UUID actorId, UUID userId, UUID permissionId)` — record `PERMISSION_REVOKED`

**Acceptance Criteria:**
- [x] Role name immutable after creation — no setter on `name`; service rejects name update attempts
- [x] System roles (`is_system_role = true`) cannot be deleted via API
- [x] System permissions (`is_system_perm = true`) cannot be deleted via API
- [x] `assignPermissionToRole` is idempotent — second call with same args produces no error and no duplicate record

**Automated Tests:**
- [x] `RoleServiceTest`:
  - Duplicate name throws `ConflictException`
  - Delete role with active users throws `ConflictException`
  - System role delete throws `ConflictException`
- [x] `RbacAssignmentServiceTest`:
  - Idempotent assign (second call is no-op)
  - Revoke non-existent assignment is a no-op
  - Audit event recorded on each assign/revoke

---

### Phase 6.7 — Admin Security Operations Service

**Implementation Tasks:**

- [x] Create `AdminSecurityService.java`:
  - `void forceReAuthentication(UUID actorId, UUID targetUserId)` — updates `credentials_updated_at`, records `CREDENTIALS_INVALIDATED` with reason `ADMIN_FORCED_REAUTH`
  - `void manualLockAccount(UUID actorId, UUID targetUserId, String reason)` — sets `LOCKED` status, permanent lockout (`lockoutType = MANUAL`, no `expiresAt`), updates `credentialsUpdatedAt`, records `ACCOUNT_LOCKED` and `CREDENTIALS_INVALIDATED`
  - `void unlockAccount(UUID actorId, UUID targetUserId)` — transitions to `ACTIVE`, clears `lockoutExpiresAt`, resets failed attempts, records `ACCOUNT_UNLOCKED`
  - `SecuritySummaryResponse getSecuritySummary()` — aggregate counts: locked accounts, failures in last N hours, forced re-auths in last N hours

**Acceptance Criteria:**
- [x] `forceReAuthentication` immediately invalidates all existing JWTs for target user
- [x] `manualLockAccount` permanent lock is not cleared by time-based logic — only by explicit `unlockAccount`
- [x] `getSecuritySummary` returns aggregate counts only — no individual PII

**Automated Tests:**
- [x] `AdminSecurityServiceTest`:
  - Force re-auth → JWT with old `iat` rejected on next request
  - Manual lock → user cannot authenticate even after auto-lockout window expires
  - Unlock → user can authenticate immediately

---

## Phase 7 — DTO Layer & Input Validation

**Objective:** Define all request and response DTOs with Jakarta Validation constraints. Establish global exception handling with RFC 7807 Problem Details.

**Dependencies:** Phase 6 complete.

**Complexity:** Medium

### Phase 7.1 — Request DTOs

**Implementation Tasks:**

- [x] `RegisterRequest`: `@NotBlank @Email String email`, `@NotBlank @ValidPassword String password`, `@AssertTrue boolean consentAccepted`
- [x] `LoginRequest`: `@NotBlank String email`, `@NotBlank String password`
- [x] `PasswordResetRequest`: `@NotBlank @Email String email`
- [x] `PasswordResetCompleteRequest`: `@NotBlank String token`, `@NotBlank @ValidPassword String newPassword`
- [x] `EmailVerifyRequest`: `@NotBlank String token`
- [x] `ResendVerificationRequest`: `@NotBlank @Email String email`
- [x] `ChangePasswordRequest`: `@NotBlank String currentPassword`, `@NotBlank @ValidPassword String newPassword`
- [x] `EmailChangeRequest`: `@NotBlank @Email String newEmail`
- [x] `UpdateProfileRequest`: `@Size(max=100) String displayName`
- [x] `CreateRoleRequest`: `@NotBlank @Size(max=100) String name`, `String description`
- [x] `UpdateRoleRequest`: `String description` only (name excluded)
- [x] `CreatePermissionRequest`: `@NotBlank @Pattern(regexp="[a-z]+:[a-z]+") String name`, `String description`, `UUID categoryId`
- [x] `AssignPermissionRequest`: `@NotNull UUID permissionId`
- [x] `AssignRoleRequest`: `@NotNull UUID roleId`
- [x] `AdminCreateUserRequest`: `@NotBlank @Email String email`, `List<UUID> roleIds`
- [x] `ForceReAuthRequest`: `@NotNull UUID targetUserId`
- [x] `ManualLockRequest`: `@NotNull UUID targetUserId`, `@NotBlank String reason`
- [x] `AuditLogFilterRequest`: `String eventTypeSlug`, `UUID actorId`, `UUID targetId`, `Instant from`, `Instant to`, `String outcomeSlug`

**Acceptance Criteria:**
- [x] All DTOs are `record`s (Java 25 idiom) or `@Value`-annotated Lombok classes — immutable
- [x] `RegisterRequest.password` and `LoginRequest.password` have `@JsonProperty(access = WRITE_ONLY)` — never serialized in responses
- [x] `consentAccepted` is `@AssertTrue` — registration rejected without explicit consent

**Automated Tests:**
- [x] `DtoValidationTest` — unit tests for each constraint using `Validator` directly; covers happy path and every violation case

---

### Phase 7.2 — Response DTOs

**Implementation Tasks:**

- [x] `AuthResponse`: `String accessToken`, `String tokenType = "Bearer"`, `int expiresInSeconds`
- [x] `UserProfileResponse`: `UUID id`, `String maskedEmail`, `String displayName`, `String accountStatus`, `String authOrigin`, `Instant lastLoginAt`, `List<String> roles`, `List<String> directPermissions`, `Instant createdAt`
- [x] `UserAdminResponse`: extends `UserProfileResponse` + `int failedLoginAttempts`, `String lockoutStatus`, `Instant lockoutExpiresAt`, `boolean isDeleted`
- [x] `UserSummaryResponse`: `UUID id`, `String maskedEmail`, `String accountStatus`, `String authOrigin`, `Instant lastLoginAt` (pagination result)
- [x] `RoleResponse`: `UUID id`, `String name`, `String description`, `boolean isSystemRole`, `int permissionCount`, `Instant createdAt`
- [x] `PermissionResponse`: `UUID id`, `String name`, `String description`, `String category`, `boolean isSystemPerm`
- [x] `AuditLogResponse`: `UUID id`, `String eventType`, `String outcome`, `String severity`, `UUID actorUserId`, `UUID targetUserId`, `String ipAddressMasked`, `String correlationId`, `Map<String, Object> metadata`, `Instant createdAt`
- [x] `ErrorResponse`: `String errorCode`, `String message`, `String correlationId`, `Instant timestamp`, `Map<String, String> fieldErrors` (nullable, for validation errors)
- [x] `PageResponse<T>`: `List<T> content`, `int page`, `int size`, `long totalElements`, `int totalPages`
- [x] `SecuritySummaryResponse`: `int lockedAccountsCount`, `int failedAttemptsLast24h`, `int forcedReAuthsLast24h`

**Acceptance Criteria:**
- [x] No response DTO contains `passwordHash`, raw token values, full email (use masked), or internal exception details
- [x] `AuditLogResponse.metadata` passes through `AuditMetadataSanitizer` before being added to DTO
- [x] `AuthResponse` has `Cache-Control: no-store` set at controller level

---

### Phase 7.3 — Global Exception Handler (RFC 7807)

**Implementation Tasks:**

- [x] Create `GlobalExceptionHandler.java @RestControllerAdvice`:
  - `handleMethodArgumentNotValid(MethodArgumentNotValidException)` → 400 with field-level errors in `ErrorResponse.fieldErrors`
  - `handleConstraintViolation(ConstraintViolationException)` → 400
  - `handleInvalidCredentials(InvalidCredentialsException)` → 401 generic message (never "wrong password" or "user not found")
  - `handleTokenExpired(TokenExpiredException)` → 400 or 401 depending on context
  - `handleResourceNotFound(ResourceNotFoundException)` → 404
  - `handleConflict(ConflictException)` → 409
  - `handleAccessDenied(AccessDeniedException)` → 403 (Spring Security; no permission name in response)
  - `handleAuthException(AuthException)` → 401 generic
  - `handleMethodNotAllowed(HttpRequestMethodNotSupportedException)` → 405
  - `handleAll(Exception)` → 500 with generic message (no stack trace)
  - All handlers include `correlationId` from `CorrelationIdHolder`
  - Log all 5xx exceptions at ERROR level with stack trace internally; never in response

**Acceptance Criteria:**
- [x] 400 response always includes `fieldErrors` map for validation failures
- [x] 401 response message is generic — no user-existence hint
- [x] 403 response does not name required permission
- [x] 500 response contains `correlationId` but no stack trace, class names, or SQL
- [x] All responses have `Content-Type: application/json`

**Automated Tests:**
- [x] `GlobalExceptionHandlerTest` (`@WebMvcTest`):
  - Invalid DTO → 400 with fieldErrors populated
  - `InvalidCredentialsException` → 401 with generic message
  - `ResourceNotFoundException` → 404
  - `ConflictException` → 409
  - Unhandled exception → 500 without stack trace
  - All responses contain `correlationId`

---

## Phase 8 — Controller Layer

**Objective:** Implement all `@RestController` classes. Controllers validate input via `@Valid`, delegate to services, and return standardized DTOs. No business logic in controllers.

**Dependencies:** Phase 7 complete.

**Complexity:** Medium

### Phase 8.1 — Authentication Controller

**Implementation Tasks:**

- [x] Create `AuthController.java @RestController @RequestMapping("/api/v1/auth")`:
  - `POST /register` → `authService.register()` → 201
  - `POST /login` → `authService.login()` → 200 with `Cache-Control: no-store`
  - `POST /logout` → `authService.logout()` → 204 (requires valid JWT)
  - `GET /me` → `userService.getOwnProfile()` → 200 (requires JWT)
  - `POST /password/change` → `authService.changePassword()` → 204 (requires JWT)
  - `POST /password-reset/request` → `passwordResetService.initiateReset()` → 200
  - `POST /password-reset/confirm` → `passwordResetService.completeReset()` → 200
  - `GET /email/verify` → `emailVerificationService.verifyEmail()` → 200
  - `POST /email/verify/resend` → `emailVerificationService.resendVerification()` → 200
  - `POST /email/change` → `emailVerificationService.initiateEmailChange()` → 200 (requires JWT)
  - `@Valid` on all request body parameters
  - `@PreAuthorize("isAuthenticated()")` on endpoints requiring JWT

**Acceptance Criteria:**
- [x] `POST /login` response includes `Cache-Control: no-store` header
- [x] `POST /register` returns 201 with uniform success message regardless of email conflict
- [x] `POST /password-reset/request` always returns 200 regardless of email existence
- [x] No controller method contains business logic (only delegation + response mapping)

**Automated Tests:**
- [x] `AuthControllerTest` (`@WebMvcTest`):
  - Valid register → 201
  - Login with mocked service → 200 with JWT in body
  - Login missing body → 400 with fieldErrors
  - Logout without token → 401
  - `POST /password-reset/request` with non-existent email → 200 (anti-enumeration)

---

### Phase 8.2 — User Management Controller

**Implementation Tasks:**

- [x] Create `UserController.java @RestController @RequestMapping("/api/v1/users")`:
  - `GET /me` → own profile (requires JWT)
  - `PUT /me` → update own profile (requires JWT)
  - `GET /{userId}` → `@PreAuthorize("hasAuthority('user:read')")` → admin view
  - `GET /` → `@PreAuthorize("hasAuthority('user:read')")` → paginated list
  - `POST /` → `@PreAuthorize("hasAuthority('user:create')")` → admin create
  - `PUT /{userId}/disable` → `@PreAuthorize("hasAuthority('user:update')")`
  - `PUT /{userId}/activate` → `@PreAuthorize("hasAuthority('user:update')")`
  - `DELETE /{userId}` → `@PreAuthorize("hasAuthority('user:delete')")` → soft delete

**Automated Tests:**
- [x] `UserControllerTest` (`@WebMvcTest`):
  - Unauthenticated `GET /me` → 401
  - `GET /{userId}` without `user:read` authority → 403
  - `GET /{userId}` response never contains `passwordHash`
  - Pagination parameters validated (page ≥ 0, size 1–100)

---

### Phase 8.3 — RBAC Controllers

**Implementation Tasks:**

- [x] Create `RoleController.java @RequestMapping("/api/v1/roles")`:
  - `POST /` → `@PreAuthorize("hasAuthority('role:create')")`
  - `GET /` → `@PreAuthorize("hasAnyAuthority('role:create','role:update')")`
  - `GET /{roleId}` → `@PreAuthorize("hasAnyAuthority('role:create','role:update')")`
  - `PUT /{roleId}` → `@PreAuthorize("hasAuthority('role:update')")`
  - `DELETE /{roleId}` → `@PreAuthorize("hasAuthority('role:delete')")`
  - `POST /{roleId}/permissions` → `@PreAuthorize("hasAuthority('permission:grant')")`
  - `DELETE /{roleId}/permissions/{permissionId}` → `@PreAuthorize("hasAuthority('permission:revoke')")`
- [x] Create `PermissionController.java @RequestMapping("/api/v1/permissions")`:
  - `POST /` → `@PreAuthorize("hasAuthority('permission:grant')")`
  - `GET /` → `@PreAuthorize("hasAnyAuthority('permission:grant','audit:view')")`
  - `DELETE /{permissionId}` → `@PreAuthorize("hasAuthority('permission:revoke')")`
- [x] Create `UserRoleController.java @RequestMapping("/api/v1/users/{userId}/roles")`:
  - `POST /` → `@PreAuthorize("hasAuthority('role:update')")`
  - `DELETE /{roleId}` → `@PreAuthorize("hasAuthority('role:update')")`
- [x] Create `UserPermissionController.java @RequestMapping("/api/v1/users/{userId}/permissions")`:
  - `POST /` → `@PreAuthorize("hasAuthority('permission:grant')")`
  - `DELETE /{permissionId}` → `@PreAuthorize("hasAuthority('permission:revoke')")`

**Automated Tests:**
- [x] `RoleControllerTest`: missing authority → 403; duplicate name → 409; system role delete → 409
- [x] Authorization matrix test: matrix of all endpoints × all permission combinations

---

### Phase 8.4 — Audit & Admin Security Controllers

**Implementation Tasks:**

- [x] Create `AuditController.java @RequestMapping("/api/v1/audit")`:
  - `GET /` → `@PreAuthorize("hasAuthority('audit:view')")` → paginated, filtered
  - `GET /users/{userId}` → `@PreAuthorize("hasAuthority('audit:view')")` → per-user timeline
  - `GET /summary` → `@PreAuthorize("hasAuthority('audit:view')")` → security summary
- [x] Create `AdminSecurityController.java @RequestMapping("/api/v1/admin/security")`:
  - `POST /force-reauth` → `@PreAuthorize("hasAuthority('auth:manage')")`
  - `POST /lock` → `@PreAuthorize("hasAuthority('auth:manage')")`
  - `POST /unlock` → `@PreAuthorize("hasAuthority('auth:manage')")`

**Acceptance Criteria:**
- [x] Audit log API is read-only (no POST/PUT/DELETE on audit entries)
- [x] All responses paginated where list endpoints are involved
- [x] No raw user data in audit responses — masked/truncated only

**Automated Tests:**
- [x] `AuditControllerTest`: without `audit:view` → 403; pagination parameters honored; no PII in response

---

## Phase 9 — OAuth2 Google Integration

**Objective:** Implement Google OAuth2 login, auto-registration, and account linking. The resulting JWT is structurally identical to one issued via local login.

**Dependencies:** Phase 6 and 8 complete.

**Complexity:** High

### Phase 9.1 — OAuth2 Success Handler

**Implementation Tasks:**

- [x] Create `OAuth2AuthenticationSuccessHandler.java implements AuthenticationSuccessHandler`:
  - Extract `OidcUser` or `OAuth2User` from authentication
  - Extract: `email`, `displayName`, `providerUserId` (Google `sub` claim)
  - Resolve `OauthProvider` entity for Google by slug
  - Look up `OauthAccount` by `(providerId, providerUserIdValue)`:
    - If found and not unlinked: this is a returning user → proceed to JWT issuance
    - If not found: check if email exists as LOCAL account
      - If email exists (LOCAL account): link accounts → update `auth_origin` to `MIXED`, create `OauthAccount`, record `ACCOUNT_LINKED_GOOGLE`
      - If email does not exist: create new `User` with status `ACTIVE`, `emailVerifiedAt = now()`, `origin = GOOGLE`, create `OauthAccount`, record `USER_REGISTERED_GOOGLE`
  - Resolve effective permissions via `PermissionResolver`
  - Issue JWT via `JwtService`
  - Redirect to `${OAUTH2_SUCCESS_REDIRECT_URL}?token=<jwt>` (frontend receives token in query param)
  - Record `AUTH_SUCCESS` audit event
  - Google OAuth2 access/refresh tokens are **not stored** — discarded after profile extraction
- [x] Create `OAuth2UserInfoExtractor.java` — extracts and validates required fields from `OAuth2User`; throws `OAuthProviderException` if email or sub is missing

**Acceptance Criteria:**
- [x] New Google user: `ACTIVE` account created, email marked verified
- [x] Existing local user: accounts linked to `MIXED`, local password preserved
- [x] Returning Google user: JWT issued, `last_used_at` updated on `oauth_accounts`
- [x] Google tokens (access/refresh) are not persisted anywhere
- [x] Brute-force lockout check applied after user resolution

**Automated Tests:**
- [x] `OAuth2SuccessHandlerTest` (unit, mocked repos):
  - New user flow creates `User` + `OauthAccount`
  - Existing local user flow creates `OauthAccount` and sets origin to `MIXED`
  - Returning Google user updates `lastUsedAt`
  - Missing email in Google profile throws `OAuthProviderException`

---

### Phase 9.2 — OAuth2 Failure Handler & Unlink Flow

**Implementation Tasks:**

- [x] Create `OAuth2AuthenticationFailureHandler.java implements AuthenticationFailureHandler`:
  - Log failure reason internally (not forwarded to client)
  - Redirect to `${OAUTH2_FAILURE_REDIRECT_URL}?error=oauth_failed`
  - Record `AUTH_FAILURE` audit event with provider context in metadata
  - No partial user records created
- [x] Create `OAuthProviderService.java`:
  - `void unlinkProvider(UUID userId, String providerSlug)`:
    1. Find active `OauthAccount` for user + provider
    2. If user is `GOOGLE`-only with no local password: reject with 409 (would lock out user)
    3. Set `unlinkedAt = now()` on `OauthAccount`
    4. If was `MIXED`: set `authOrigin` back to `LOCAL`
    5. Optionally update `credentialsUpdatedAt` per config
    6. Record `PROVIDER_UNLINKED` audit event
- [x] Add `DELETE /api/v1/auth/provider/{providerSlug}` → `@PreAuthorize("isAuthenticated()")`

**Acceptance Criteria:**
- [x] OAuth2 failure produces no orphaned user records
- [x] Google-only account cannot unlink without a local password set
- [x] State parameter CSRF validation is handled by Spring Security OAuth2 Client by default (must not disable it)

**Automated Tests:**
- [x] `OAuth2FailureHandlerTest` — no DB writes on failure; audit event recorded
- [x] `OAuthProviderServiceTest` — GOOGLE-only user unlink throws `ConflictException`; MIXED user unlink succeeds

---

## Phase 10 — Brute Force Protection & Rate Limiting

**Objective:** Implement per-account lockout threshold logic and IP-based rate limiting as independent defense layers.

**Dependencies:** Phase 6.2 (AuthService has lockout hooks).

**Complexity:** Medium

### Phase 10.1 — Login Attempt Tracking & Lockout

**Implementation Tasks:**

- [x] Create `BruteForceProtectionService.java`:
  - `void recordAttempt(UUID userId, String ipMasked, String userAgentTruncated, String authMethodSlug, boolean success, String failureContext)` — saves `LoginAttempt`
  - `boolean isAccountLocked(User user)` — checks `lockoutExpiresAt` against `now()`; auto-clears expired automatic lockouts
  - `void incrementFailedAttempts(User user)` — increments counter; if threshold reached: sets lockout fields, saves `AccountLockout` record, records `ACCOUNT_LOCKED` audit event
  - `void resetFailedAttempts(User user)` — resets counter to 0, clears `lockoutExpiresAt`
  - All thresholds driven by `AppProperties` (not hardcoded)

**Acceptance Criteria:**
- [x] AUTOMATIC lockout: `lockoutExpiresAt = now() + lockoutDurationMinutes`, auto-clears after window
- [x] MANUAL lockout: no `lockoutExpiresAt`, not cleared by time passage — only by admin `unlockAccount`
- [x] Failed attempt counter resets on any successful login
- [x] `failure_context` stored internally; **never** returned to API caller

**Automated Tests:**
- [x] `BruteForceProtectionServiceTest`:
  - 5 failures → account locked
  - Successful login → counter reset
  - Expired auto-lockout → cleared on next attempt
  - MANUAL lock → not cleared by time
- [x] `AccountLockoutIntegrationTest`:
  - 5 failed login requests → 6th request returns same 401 (lockout active)
  - Wait for lockout window (or manipulate DB) → login succeeds

---

### Phase 10.2 — Rate Limiting Filter

**Implementation Tasks:**

- [x] Create `RateLimitingFilter.java extends OncePerRequestFilter`:
  - Applies to: `/api/v1/auth/login`, `/api/v1/auth/register`, `/api/v1/auth/password-reset/request`, `/api/v1/auth/email/verify/resend`
  - IP-based rate limiting using in-memory `ConcurrentHashMap<String, RateLimitBucket>` (or Resilience4j/Bucket4j if on classpath)
  - On limit exceeded: return 429 with `Retry-After` header
  - 429 response body: `{ "errorCode": "RATE_LIMITED", "message": "Too many requests.", "correlationId": "..." }`
  - 429 response must not distinguish IP-based from account-based limiting
  - Rate limit parameters configurable via `AppProperties`

**Acceptance Criteria:**
- [x] Exceeding limit returns exactly HTTP 429 with `Retry-After` header
- [x] Rate limiting is independent of per-account lockout logic
- [x] Filter does not apply to non-auth endpoints

**Automated Tests:**
- [x] `RateLimitingFilterTest`:
  - N+1 requests within window → 429
  - Different IPs have independent rate limit buckets
  - After window reset → requests succeed again

---

## Phase 11 — LGPD, Privacy & Data Retention

**Objective:** Implement all LGPD-mandated controls: consent capture, soft-delete, anonymization readiness, and token retention cleanup.

**Dependencies:** Phase 6 complete.

**Complexity:** Medium

### Phase 11.1 — Consent Capture

**Implementation Tasks:**

- [x] `RegisterRequest.consentAccepted` → `@AssertTrue` (already defined in Phase 7.1)
- [x] In `AuthService.register()`: persist `UserConsent` record with `consentVersion` from `AppProperties`, masked IP, truncated user-agent, `acceptedAt = now()`
- [x] Also update denormalized `users.consentAcceptedAt` and `users.consentVersion`
- [x] Record `CONSENT_ACCEPTED` audit event with `consentVersion` in metadata
- [x] Create `GET /api/v1/users/me/consents` endpoint → returns consent history for own account

**Acceptance Criteria:**
- [x] Registration without `consentAccepted: true` returns 400
- [x] `UserConsent` record persisted on every registration with correct `consentVersion`
- [x] Audit event `CONSENT_ACCEPTED` recorded with version in metadata

**Automated Tests:**
- [x] `ConsentCaptureTest` — registration without consent fails; with consent persists `UserConsent`

---

### Phase 11.2 — Token Retention Cleanup

**Implementation Tasks:**

- [x] Create `TokenRetentionService.java @Scheduled`:
  - [x] `purgeExpiredPasswordResetTokens()` — deletes consumed tokens older than `retentionDays` from `password_reset_tokens`
  - [x] `purgeExpiredVerificationTokens()` — deletes consumed/invalidated tokens past retention
  - [x] Scheduled via `@Scheduled(cron = "0 0 2 * * *")` (2 AM daily)
  - [x] Purge operations recorded as audit events
  - [x] Batch delete to avoid large transaction locks
- [x] Enable `@EnableScheduling` in configuration
- [x] Expose purge configuration: `app.retention.password-reset-days`, `app.retention.verification-token-days`

**Acceptance Criteria:**
- [x] Consumed tokens older than retention threshold are deleted on schedule
- [x] Purge does not delete tokens still within retention window
- [x] Purge event itself recorded in `audit_logs`

**Automated Tests:**
- [x] `TokenRetentionServiceTest` — insert expired consumed tokens; run purge; assert deleted; assert non-expired tokens preserved

---

### Phase 11.3 — Anonymization Pipeline Scaffold

**Implementation Tasks:**

- [x] Create `AnonymizationService.java` (scaffold, not yet triggered by API):
  - [x] `void anonymizeUser(UUID userId)` — zeroes `email`, `display_name`, `password_hash`, sets `anonymized_at = now()`
  - [x] Validates: user must already be soft-deleted (`deleted_at IS NOT NULL`)
  - [x] UUID row preserved; all `audit_logs` FK references remain valid
  - [x] Records audit event with `anonymized_at` timestamp in metadata
- [x] This service is not wired to an API endpoint in this phase — it is the foundation for a future LGPD erasure request endpoint

**Acceptance Criteria:**
- [x] Anonymized user's email, displayName, passwordHash are null or zeroed
- [x] UUID row still exists — audit trail FK integrity preserved
- [x] Cannot anonymize a non-soft-deleted user

**Automated Tests:**
- [x] `AnonymizationServiceTest` — anonymize soft-deleted user; assert PII fields null; assert UUID preserved; assert non-deleted user throws exception

---

## Phase 12 — Observability & Structured Logging

**Objective:** Implement structured JSON logging, MDC correlation ID propagation, and Spring Actuator endpoints.

**Dependencies:** Phase 5 (MDC/Correlation ID) complete.

**Complexity:** Low

### Phase 12.1 — Structured JSON Logging

**Implementation Tasks:**

- [x] Add `net.logstash.logback:logstash-logback-encoder` dependency
- [x] Create `src/main/resources/logback-spring.xml`:
  - Production profile: `LogstashEncoder` for JSON output
  - Dev profile: `ConsoleAppender` with readable pattern
  - Include MDC fields in every log entry: `correlationId`, `userId` (when authenticated)
  - Set log levels via environment variable: `${LOG_LEVEL_ROOT:INFO}`, `${LOG_LEVEL_APP:DEBUG}`
- [x] Configure `LogstashEncoder` custom fields: `appName`, `environment`
- [x] Log sensitive field blocklist: verify no log appender outputs `password`, `token`, `secret`, `hash` at any log level

**Acceptance Criteria:**
- [x] Every log line in production is valid JSON
- [x] `correlationId` present in every log line for a request
- [x] No password, token, or secret value appears in any log at DEBUG level or above

**Automated Tests:**
- [x] `StructuredLoggingTest` — capture log output during a request; assert JSON parseable; assert `correlationId` present; assert no sensitive field names contain values

---

### Phase 12.2 — Actuator Health Endpoints

**Implementation Tasks:**

- [x] Configure Actuator: expose `health` and `info` only
- [x] Add custom `HealthIndicator` for DB connectivity check
- [x] `GET /actuator/health` → `{ "status": "UP" }` when DB is reachable
- [x] `GET /actuator/health/liveness` and `/readiness` for Kubernetes probes
- [x] Disable all other actuator endpoints in production

**Acceptance Criteria:**
- [x] `GET /actuator/health` returns 200 with `status: UP` when application is healthy
- [x] Returns 503 when DB is unreachable
- [x] No sensitive information exposed via actuator

**Automated Tests:**
- [x] `ActuatorHealthTest` — `GET /actuator/health` returns 200; `GET /actuator/env` returns 404 (disabled)

---

## Phase 13 — OpenAPI Documentation

**Objective:** Document all API endpoints with OpenAPI 3.x via SpringDoc. Provide frontend-ready auth flow contracts.

**Dependencies:** Phase 8 complete.

**Complexity:** Low

### Phase 13.1 — OpenAPI Configuration

**Implementation Tasks:**

- [x] Create `OpenApiConfig.java @Configuration`:
  - `OpenAPI` bean with: title, description, version, contact, license
  - `SecurityScheme`: `bearerAuth`, type `HTTP`, scheme `bearer`, bearerFormat `JWT`
  - `SecurityRequirement` applied globally
- [x] Annotate all controllers with `@Tag(name = "...")` for grouping
- [x] Annotate all endpoints with `@Operation(summary = "...", description = "...")`
- [x] Document all response codes with `@ApiResponse`: 200, 201, 400, 401, 403, 404, 409, 429, 500
- [x] Document `ErrorResponse` schema with `@Schema`
- [x] Document auth flow in `description` field:
  - JWT Bearer: `Authorization: Bearer <token>` header
  - OAuth2 Google: redirect flow with token returned in redirect URL
  - Token expiry: re-authenticate via `POST /api/v1/auth/login`

**Acceptance Criteria:**
- [x] `GET /v3/api-docs` returns valid OpenAPI 3.1 JSON
- [x] `GET /swagger-ui/index.html` renders all endpoints
- [x] JWT Bearer auth scheme configurable in Swagger UI (test authenticated requests)
- [x] No internal class names or package paths in API documentation

**Automated Tests:**
- [x] `OpenApiTest` — `GET /v3/api-docs` returns 200; response parses as valid OpenAPI JSON; all expected paths present

---

## Phase 14 — Comprehensive Test Suite

**Objective:** Build the complete automated test suite covering all layers, security properties, and authorization matrix.

**Dependencies:** All previous phases complete.

**Complexity:** High

### Phase 14.1 — Unit Test Suite

**Implementation Tasks:**

- [x] `JwtServiceTest` — generation, validation, expiry, tampered signature, PII-free payload
- [x] `PasswordEncoderTest` — Argon2id prefix, match/no-match, different salts
- [x] `PasswordPolicyTest` — all strength rules
- [x] `DataMaskerTest` — all masking functions, edge cases
- [x] `AuditMetadataSanitizerTest` — blocklist, email detection
- [x] `PermissionResolverTest` — union logic, deduplication
- [x] `BruteForceProtectionServiceTest` — threshold, lockout, reset
- [x] `AuthServiceTest` — registration, login, logout, password change (all mocked)
- [x] `PasswordResetServiceTest` — initiation, completion, expiry, consumed token
- [x] `EmailVerificationServiceTest` — verify, resend, idempotency
- [x] `UserServiceTest` — profile, disable, activate, soft delete
- [x] `RoleServiceTest` — CRUD, system role protection, deletion guard
- [x] `RbacAssignmentServiceTest` — idempotent assign, revoke, audit events
- [x] `AdminSecurityServiceTest` — force re-auth, manual lock, unlock
- [x] `OAuthProviderServiceTest` — unlink flows

**Target:** >85% line coverage on service layer.

---

### Phase 14.2 — Repository Tests (`@DataJpaTest` + Testcontainers)

**Implementation Tasks:**

- [x] `UserRepositoryTest` — soft-delete filter, email lookup, pagination
- [x] `PasswordResetTokenRepositoryTest` — active token lookup, invalidation query
- [x] `EmailVerificationTokenRepositoryTest` — active token lookup, batch invalidation
- [x] `AuditLogRepositoryTest` — time-range queries, user-scoped pagination, ordering
- [x] `LoginAttemptRepositoryTest` — count query within time window
- [x] `AccountLockoutRepositoryTest` — active lockout lookup
- [x] `RolePermissionRepositoryTest` — idempotent insert, delete
- [x] `UserRoleRepositoryTest` — idempotent insert, delete

---

### Phase 14.3 — Controller Tests (`@WebMvcTest`)

**Implementation Tasks:**

- [x] `AuthControllerTest` — all endpoints, validation failures, anti-enumeration responses
- [x] `UserControllerTest` — auth requirements, PII-free responses
- [x] `RoleControllerTest` — permission guards, conflict scenarios
- [x] `AuditControllerTest` — read-only enforcement, pagination
- [x] `AdminSecurityControllerTest` — `auth:manage` authority requirement
- [x] `GlobalExceptionHandlerTest` — all exception → HTTP status mappings

---

### Phase 14.4 — Integration Tests (`@SpringBootTest` + Testcontainers)

**Implementation Tasks:**

- [x] `AuthIntegrationTest`:
  - Full register → verify email → login → access protected endpoint → logout flow
  - Login with expired token → 401
  - Login with stale token (after password change) → 401
  - Re-authenticate after password change → success
- [x] `AccountLockoutIntegrationTest`:
  - 5 failed logins → 6th attempt → 401 with same message (lockout transparent)
  - Admin unlock → login succeeds
- [x] `PasswordResetIntegrationTest`:
  - Full flow: register → verify → reset → login with new password → old token rejected
- [x] `OAuth2IntegrationTest`:
  - Mock OAuth2 provider using `WireMock` or Spring's `MockServerHttpConnector`
  - New user registration via OAuth2
  - Existing user login via OAuth2
  - Account linking (LOCAL → MIXED)
- [x] `RbacIntegrationTest`:
  - User without permission → 403
  - User with permission → 200
  - Permission revoked → JWT expired → new login → 403
- [x] `CredentialsInvalidationIntegrationTest`:
  - Issue JWT → change password → use old JWT → 401
  - Issue JWT → admin force-reauth → use old JWT → 401
  - Issue JWT → admin disable user → use old JWT → 401

---

### Phase 14.5 — Security Tests

**Implementation Tasks:**

- [x] `JwtPayloadSecurityTest`:
  - JWT `sub` claim is UUID format (never contains `@`)
  - JWT `authorities` claim has no PII fields (no email, name, CPF)
  - JWT does not contain `password`, `hash`, `email`, `name` claims
  - Token size < 4 KB for typical permission set
- [x] `AntiEnumerationTest`:
  - `POST /register` with existing email → same 201 response shape as new registration
  - `POST /login` with non-existent email → same 401 body as wrong password
  - `POST /login` with locked account → same 401 body as wrong password
  - `POST /password-reset/request` with non-existent email → 200
  - `GET /auth/email/verify` with invalid token → generic error (no email in message)
- [x] `SecurityHeadersTest` — all required headers present on all responses
- [x] `CsrfProtectionTest` — `POST` without CSRF token accepted (stateless JWT)
- [x] `CorsTest` — wildcard origin rejected; allowed origin accepted
- [x] `AuthorizationMatrixTest`:
  - Matrix of all sensitive endpoints × all permission strings
  - Each combination asserts correct HTTP status (200 vs 403)
- [x] `SensitiveFieldLeakTest`:
  - All API response bodies parsed; assert no field named `password`, `hash`, `token`, `secret`
  - 500 error response contains no stack trace

---

### Phase 14.6 — Flyway Migration Validation Tests

**Implementation Tasks:**

- [x] `FlywayMigrationTest`:
  - All migrations apply cleanly on fresh PostgreSQL 18 container
  - Migration count matches expected count
  - Checksums verified (no tampering)
- [x] `SeedDataVerificationTest` — all expected seed slugs present post-migration
- [x] `SchemaConstraintTest` — insert duplicate email → `DataIntegrityViolationException`; insert duplicate role name → exception
- [x] `IndexPresenceTest` — verify critical indexes exist via `pg_indexes` query

---

## Phase 15 — Docker & CI/CD Readiness

**Objective:** Produce Docker artifacts and CI/CD pipeline configuration for production deployment readiness.

**Dependencies:** All phases complete.

**Complexity:** Low

### Phase 15.1 — Dockerfile (Multi-Stage Build)

**Implementation Tasks:**

- [x] Create `Dockerfile`:
  ```dockerfile
  # Stage 1: Build
  FROM eclipse-temurin:25-jdk-alpine AS builder
  WORKDIR /app
  COPY gradlew settings.gradle.kts build.gradle.kts ./
  COPY gradle/ gradle/
  RUN ./gradlew dependencies --no-daemon
  COPY src/ src/
  RUN ./gradlew bootJar --no-daemon -x test

  # Stage 2: Runtime
  FROM eclipse-temurin:25-jre-alpine AS runtime
  RUN addgroup -S appgroup && adduser -S appuser -G appgroup
  WORKDIR /app
  COPY --from=builder /app/build/libs/*.jar app.jar
  USER appuser
  EXPOSE 8080
  ENTRYPOINT ["java", "-jar", "app.jar"]
  ```
- [x] Add `.dockerignore` — exclude `.git`, `build/`, `.idea/`, `*.md`, `docs/`
- [x] Verify image builds without secrets baked in
- [x] Verify image size < 300 MB (JRE-only runtime stage)

**Acceptance Criteria:**
- [x] `docker build -t java-auth-template .` succeeds
- [x] Container starts with environment variables injected via `docker run -e`
- [x] Container fails fast with clear message if `DB_URL` is absent
- [x] No secrets in Dockerfile or image layers

---

### Phase 15.2 — Docker Compose (Local Development)

**Implementation Tasks:**

- [x] Create `docker-compose.yml`:
  - `postgres` service: PostgreSQL 18, named volume, health check
  - `app` service: depends on `postgres`, environment variables from `.env`
  - Network isolation between services
- [x] Create `docker-compose.override.yml` for local dev (hot-reload, debug port)
- [x] Document startup sequence in `.env.example`
- [x] `docker compose up` starts a working dev environment with Flyway migrations applied

**Acceptance Criteria:**
- [x] `docker compose up -d` starts PostgreSQL and application
- [x] Flyway migrations run automatically on app startup
- [x] `GET /actuator/health` returns 200 within 30 seconds of container start

---

### Phase 15.3 — CI/CD Pipeline Configuration

**Implementation Tasks:**

- [x] Create `.github/workflows/ci.yml`:
  - Trigger: push to `main`, pull request to `main`
  - Jobs:
    1. `build-and-test`:
       - Set up Java 25 (`eclipse-temurin`)
       - `./gradlew test` — Testcontainers starts PostgreSQL automatically
       - Publish test results as GitHub Actions summary
       - Cache Gradle dependencies
    2. `security-check`:
       - OWASP Dependency Check or `./gradlew dependencyCheckAnalyze`
       - Fail on CVSS ≥ 7.0
    3. `docker-build` (on `main` push only):
       - Build Docker image
       - Push to registry with `git sha` and `latest` tags
- [x] Gate: PRs cannot merge without CI passing

**Acceptance Criteria:**
- [x] Full test suite runs in CI without manual setup (Testcontainers handles PostgreSQL)
- [x] `GOOGLE_CLIENT_SECRET`, `JWT_SECRET` etc. are GitHub secrets — never in workflow YAML
- [x] CI runs on pull requests and blocks merge on failure

---

## Phase Summary

| Phase | Description | Complexity | Dependencies |
|---|---|---|---|
| **0** | Project Bootstrap & Build | Low | None |
| **0.1** | Gradle build configuration | Low | — |
| **0.2** | App entry point & package structure | Low | 0.1 |
| **0.3** | application.yml configuration | Low | 0.1 |
| **0.4** | Test infrastructure (Testcontainers) | Low | 0.1 |
| **1** | Database Migrations (Flyway) | Medium | Phase 0 |
| **1.1** | Flyway baseline config | Low | 0.3 |
| **1.2** | V1: Lookup tables | Medium | 1.1 |
| **1.3** | V2: Seed data | Low | 1.2 |
| **1.4** | V3: Core identity (users) | Medium | 1.3 |
| **1.5** | V4: RBAC schema | Medium | 1.4 |
| **1.6** | V5: Token tables | Low | 1.4 |
| **1.7** | V6: OAuth2, security, audit, privacy | Medium | 1.5, 1.6 |
| **1.8** | V7: RBAC seed data | Low | 1.5 |
| **2** | Domain Layer (JPA Entities) | Medium | Phase 1 |
| **2.1** | Lookup table entities | Low | 1.2, 1.3 |
| **2.2** | User entity | Medium | 1.4 |
| **2.3** | RBAC entities | Medium | 1.5 |
| **2.4** | Token, OAuth2, brute force, audit entities | Medium | 1.6, 1.7 |
| **2.5** | Domain exceptions | Low | — |
| **3** | Repository Layer | Low | Phase 2 |
| **3.1** | Core repositories | Low | 2.2, 2.3 |
| **3.2** | Token & security repositories | Low | 2.4 |
| **4** | Security Infrastructure | High | Phase 2, 3 |
| **4.1** | Password hashing (Argon2id) | Low | 0.1 |
| **4.2** | JWT service | Medium | 0.3, 2.2 |
| **4.3** | JWT authentication filter | High | 4.2, 3.1 |
| **4.4** | Custom UserDetailsService | Medium | 3.1 |
| **4.5** | Spring Security filter chain | High | 4.3, 4.4 |
| **4.6** | HTTP security headers & CORS | Low | 4.5 |
| **4.7** | Permission resolution | Medium | 3.1, 3.2 |
| **5** | Cross-Cutting Utilities | Low | Phase 2 |
| **5.1** | Data masking utilities | Low | — |
| **5.2** | Correlation ID & MDC | Low | — |
| **5.3** | Email service | Low | 0.3 |
| **6** | Service Layer | High | Phase 3, 4, 5 |
| **6.1** | Audit service | Medium | 3.2, 5.1, 5.2 |
| **6.2** | Authentication service | High | 4.1, 4.2, 4.7, 6.1 |
| **6.3** | Password reset service | Medium | 6.1, 5.3 |
| **6.4** | Email verification service | Medium | 6.1, 5.3 |
| **6.5** | User management service | Medium | 6.1 |
| **6.6** | RBAC service | Medium | 6.1, 3.1 |
| **6.7** | Admin security operations | Medium | 6.1, 6.5 |
| **7** | DTO Layer & Exception Handling | Medium | Phase 6 |
| **7.1** | Request DTOs | Low | 4.1 |
| **7.2** | Response DTOs | Low | — |
| **7.3** | Global exception handler (RFC 7807) | Medium | 2.5 |
| **8** | Controller Layer | Medium | Phase 7 |
| **8.1** | Authentication controller | Medium | 7.1, 7.2, 7.3 |
| **8.2** | User management controller | Low | 6.5 |
| **8.3** | RBAC controllers | Medium | 6.6 |
| **8.4** | Audit & admin security controllers | Low | 6.7 |
| **9** | OAuth2 Google Integration | High | Phase 6, 8 |
| **9.1** | OAuth2 success handler | High | 6.2, 4.2 |
| **9.2** | OAuth2 failure handler & unlink | Medium | 6.1 |
| **10** | Brute Force & Rate Limiting | Medium | Phase 6.2 |
| **10.1** | Login attempt tracking & lockout | Medium | 6.2 |
| **10.2** | Rate limiting filter | Medium | 4.5 |
| **11** | LGPD, Privacy & Data Retention | Medium | Phase 6 |
| **11.1** | Consent capture | Low | 6.2 |
| **11.2** | Token retention cleanup | Low | 3.2 |
| **11.3** | Anonymization pipeline scaffold | Medium | 6.5 |
| **12** | Observability & Structured Logging | Low | Phase 5 |
| **12.1** | Structured JSON logging (Logback) | Low | 5.2 |
| **12.2** | Actuator health endpoints | Low | 0.3 |
| **13** | OpenAPI Documentation | Low | Phase 8 |
| **14** | Comprehensive Test Suite | High | All phases |
| **14.1** | Unit tests | High | All |
| **14.2** | Repository tests | Medium | Phase 3 |
| **14.3** | Controller tests | Medium | Phase 8 |
| **14.4** | Integration tests | High | All |
| **14.5** | Security tests | High | Phase 4, 8 |
| **14.6** | Flyway migration validation | Medium | Phase 1 |
| **15** | Docker & CI/CD Readiness | Low | All phases |
| **15.1** | Dockerfile (multi-stage) | Low | All |
| **15.2** | Docker Compose (dev environment) | Low | 15.1 |
| **15.3** | CI/CD pipeline (GitHub Actions) | Low | All |

---

## Critical Security Invariants

These constraints must be verified at every phase where they are relevant:

| Invariant | Enforced By |
|---|---|
| `passwordHash` never in API responses | `@JsonIgnore` + DTO pattern (Phase 7.2) |
| Raw token never logged or stored | `DataMasker.sanitizeTokenValue()` + convention (Phase 5.1) |
| JWT `sub` = UUID, not email | `JwtService` + `JwtPayloadSecurityTest` (Phase 4.2, 14.5) |
| Anti-enumeration on all auth flows | `AuthService` + `AntiEnumerationTest` (Phase 6.2, 14.5) |
| `credentials_updated_at` updated on every credential-invalidating event | `AuthService`, `PasswordResetService`, `UserService` (Phase 6) |
| No ENUM types in DB | Lookup tables via Flyway (Phase 1.2) |
| `audit_logs` is append-only | No `updatedAt` on entity; no API update/delete endpoint (Phase 2.4, 8.4) |
| Soft-delete check before all auth operations | `AccountStatusChecker` (Phase 4.4) |
| `failure_context` stored internally, never returned | `LoginAttempt.failureContext` + `@JsonIgnore` (Phase 2.4) |
| Google OAuth2 access/refresh tokens not persisted | `OAuth2SuccessHandler` — discard after profile extraction (Phase 9.1) |
| CSRF disabled — JWT Bearer header is CSRF-safe | `SecurityConfig` (Phase 4.5) |
| Session creation disabled | `SessionCreationPolicy.STATELESS` (Phase 4.5) |
| All secrets from environment variables | `application.yml` binding + `AppProperties` (Phase 0.3) |

---

## Testing Checklist (Complete)

- [x] All services: >85% line coverage via unit tests
- [x] All auth flows covered by integration tests with real PostgreSQL (Testcontainers)
- [x] JWT payload PII-free assertions automated in CI
- [x] Anti-enumeration response shape equality tested for all auth endpoints
- [x] Authorization matrix test covers all sensitive endpoints × all permission strings
- [x] `credentials_updated_at` invalidation tested end-to-end (old JWT rejected after event)
- [x] Brute-force lockout threshold tested in integration (5 failures → locked)
- [x] OAuth2 flows tested with mocked provider (WireMock)
- [x] Flyway migration test runs on CI against fresh PostgreSQL 18 container
- [x] Security headers verified on all response types
- [x] Rate limiting tested (N+1 requests → 429)
- [x] Soft-delete filter tested (deleted users excluded from lookups)
- [x] LGPD: consent required for registration tested
- [x] LGPD: anonymization preserves UUID and audit FK integrity tested
- [x] No test uses mocked repositories for core auth flows — Testcontainers only

---

## Risks & Technical Notes

| Risk | Mitigation |
|---|---|
| Argon2id parameters (memory=64MB) may cause OOM in low-memory CI runners | Use BCrypt fallback with cost=12 in test profile; Argon2id in production |
| PostgreSQL 18 may not have Testcontainers image at project start | Pin to `postgres:17` until official `postgres:18` image is available |
| Google OAuth2 flow requires network access in tests | Use WireMock to mock Google authorization server; no real OAuth2 in CI |
| JWT token size growth with many permissions | Monitor token size; consider permission grouping if authorities exceed 50 strings |
| `@Async` audit writes may be lost on application crash | Accept loss for now; Phase 15 CI/CD note documents this as known trade-off for MVP |
| Flyway baseline migration must be idempotent for team environments | Use `ON CONFLICT DO NOTHING` for all seed inserts |
| `credentials_updated_at` DB read on every authenticated request | Accepted — single indexed PK lookup; sub-millisecond latency; architectural constraint |
| Spring Boot 4.0.6 / Java 25 may have breaking API changes from Boot 3.x | Follow Spring Boot 4 migration guide; monitor deprecations in each phase |

---

## Phase 16 — Verificar tecnologia

**Objective:** Verify that the complete technology stack is functional and all automated tests pass. Confirm Docker/Testcontainers availability, resolve any test failures caused by environment or configuration issues, and ensure the project is in a fully green state before continuing with further phases.

**Dependencies:** All implemented phases (0–10 currently committed).

**Complexity:** Medium

### Phase 16.1 — Verificar ambiente Docker e Testcontainers

**Implementation Tasks:**

- [x] Verify Docker daemon is running and accessible
- [x] Verify `postgres:18-alpine` image can be pulled successfully by Testcontainers
- [x] Confirm `PostgresTestContainerConfig` starts a healthy container before any integration test
- [x] Ensure `desktop-linux` Docker context (Docker Desktop on Windows) or equivalent is active
- [x] Document Docker startup requirement in `.env.example` and README

**Acceptance Criteria:**
- [x] `docker info` returns `Server Version` without error
- [x] `docker pull postgres:18-alpine` succeeds
- [x] Testcontainers integration tests do not throw `DockerClientProviderStrategy` exceptions

**Automated Tests:**
- [x] `TestContainerSmokeTest` passes — PostgreSQL container starts and accepts a JDBC connection

---

### Phase 16.2 — Verificar compilação completa

**Implementation Tasks:**

- [x] Run `./gradlew compileJava` — zero errors
- [x] Run `./gradlew compileTestJava` — zero errors
- [x] Fix any compilation warnings that indicate likely runtime failures
- [x] Confirm all imports resolve correctly across all layers

**Acceptance Criteria:**
- [x] `BUILD SUCCESSFUL` on `compileJava` and `compileTestJava`
- [x] No unresolved symbol errors in main or test sources

**Automated Tests:**
- [x] Gradle build task is the automated verification

---

### Phase 16.3 — Verificar migrações Flyway

**Implementation Tasks:**

- [x] Run all Flyway migrations on a fresh PostgreSQL 18 container
- [x] Confirm migration count matches expected number of versioned files in `db/migration`
- [x] Confirm `flyway_schema_history` checksums match — no tampered migrations
- [x] Confirm all seed data is present after migrations (lookup table rows, RBAC roles and permissions)

**Acceptance Criteria:**
- [x] `FlywayMigrationTest` passes with correct migration count
- [x] `SeedDataTest` passes — all expected slug values present
- [x] `RbacSeedTest` passes — ADMIN role has 11 permissions; USER has 0

**Automated Tests:**
- [x] `FlywayMigrationTest`
- [x] `SeedDataTest`
- [x] `RbacSeedTest`
- [x] `LookupTablesMigrationTest`

---

### Phase 16.4 — Executar todos os testes unitários

**Implementation Tasks:**

- [x] Run all unit tests (tests that use mocked dependencies, not Testcontainers)
- [x] Fix any unit test failures found
- [x] Ensure unit tests cover: JWT service, password hashing, data masking, permission resolver, DTO validation, domain exceptions

**Acceptance Criteria:**
- [x] All unit tests pass: `JwtServiceTest`, `PasswordEncoderTest`, `PasswordPolicyTest`, `DataMaskerTest`, `AuditMetadataSanitizerTest`, `PermissionResolverTest`, `AuthorityMapperTest`, `DomainExceptionTest`, `DtoValidationTest`
- [x] Zero failures on unit test classes

**Automated Tests:**
- [x] All unit test classes listed above

---

### Phase 16.5 — Executar todos os testes de integração

**Implementation Tasks:**

- [x] Run all integration tests with a live PostgreSQL Testcontainers instance
- [x] Fix any integration test failures found
- [x] Confirm all auth flows pass end-to-end: register → verify → login → logout
- [x] Confirm brute-force lockout, password reset, and credential invalidation flows pass

**Acceptance Criteria:**
- [x] `AuthIntegrationTest` passes — full auth lifecycle
- [x] `AccountLockoutIntegrationTest` passes — lockout threshold enforced
- [x] `PasswordResetIntegrationTest` passes — full reset flow
- [x] `EmailVerificationIntegrationTest` passes — verification flow
- [x] `UserManagementIntegrationTest` passes — user lifecycle (disable, delete, etc.)
- [x] `AuthApplicationTest` passes — Spring Boot context loads

**Automated Tests:**
- [x] All integration test classes listed above

---

### Phase 16.6 — Executar suite completa e confirmar verde

**Implementation Tasks:**

- [x] Run `./gradlew test` — all tests must pass
- [x] Fix any remaining failures
- [x] Confirm test report shows 0 failures

**Acceptance Criteria:**
- [x] `./gradlew test` exits with `BUILD SUCCESSFUL`
- [x] Zero test failures in the test report
- [x] All previously implemented phases remain green

**Automated Tests:**
- [x] Full Gradle test suite