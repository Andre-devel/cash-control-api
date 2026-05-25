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

