## Phase 14 — Comprehensive Test Suite

**Objective:** Build the complete automated test suite covering all layers, security properties, and authorization matrix.

**Dependencies:** All previous phases complete.

**Complexity:** High

### Phase 14.1 — Unit Test Suite

**Implementation Tasks:**

- [ ] `JwtServiceTest` — generation, validation, expiry, tampered signature, PII-free payload
- [ ] `PasswordEncoderTest` — Argon2id prefix, match/no-match, different salts
- [ ] `PasswordPolicyTest` — all strength rules
- [ ] `DataMaskerTest` — all masking functions, edge cases
- [ ] `AuditMetadataSanitizerTest` — blocklist, email detection
- [ ] `PermissionResolverTest` — union logic, deduplication
- [ ] `BruteForceProtectionServiceTest` — threshold, lockout, reset
- [ ] `AuthServiceTest` — registration, login, logout, password change (all mocked)
- [ ] `PasswordResetServiceTest` — initiation, completion, expiry, consumed token
- [ ] `EmailVerificationServiceTest` — verify, resend, idempotency
- [ ] `UserServiceTest` — profile, disable, activate, soft delete
- [ ] `RoleServiceTest` — CRUD, system role protection, deletion guard
- [ ] `RbacAssignmentServiceTest` — idempotent assign, revoke, audit events
- [ ] `AdminSecurityServiceTest` — force re-auth, manual lock, unlock
- [ ] `OAuthProviderServiceTest` — unlink flows

**Target:** >85% line coverage on service layer.

---

### Phase 14.2 — Repository Tests (`@DataJpaTest` + Testcontainers)

**Implementation Tasks:**

- [ ] `UserRepositoryTest` — soft-delete filter, email lookup, pagination
- [ ] `PasswordResetTokenRepositoryTest` — active token lookup, invalidation query
- [ ] `EmailVerificationTokenRepositoryTest` — active token lookup, batch invalidation
- [ ] `AuditLogRepositoryTest` — time-range queries, user-scoped pagination, ordering
- [ ] `LoginAttemptRepositoryTest` — count query within time window
- [ ] `AccountLockoutRepositoryTest` — active lockout lookup
- [ ] `RolePermissionRepositoryTest` — idempotent insert, delete
- [ ] `UserRoleRepositoryTest` — idempotent insert, delete

---

### Phase 14.3 — Controller Tests (`@WebMvcTest`)

**Implementation Tasks:**

- [ ] `AuthControllerTest` — all endpoints, validation failures, anti-enumeration responses
- [ ] `UserControllerTest` — auth requirements, PII-free responses
- [ ] `RoleControllerTest` — permission guards, conflict scenarios
- [ ] `AuditControllerTest` — read-only enforcement, pagination
- [ ] `AdminSecurityControllerTest` — `auth:manage` authority requirement
- [ ] `GlobalExceptionHandlerTest` — all exception → HTTP status mappings

---

### Phase 14.4 — Integration Tests (`@SpringBootTest` + Testcontainers)

**Implementation Tasks:**

- [ ] `AuthIntegrationTest`:
  - Full register → verify email → login → access protected endpoint → logout flow
  - Login with expired token → 401
  - Login with stale token (after password change) → 401
  - Re-authenticate after password change → success
- [ ] `AccountLockoutIntegrationTest`:
  - 5 failed logins → 6th attempt → 401 with same message (lockout transparent)
  - Admin unlock → login succeeds
- [ ] `PasswordResetIntegrationTest`:
  - Full flow: register → verify → reset → login with new password → old token rejected
- [ ] `OAuth2IntegrationTest`:
  - Mock OAuth2 provider using `WireMock` or Spring's `MockServerHttpConnector`
  - New user registration via OAuth2
  - Existing user login via OAuth2
  - Account linking (LOCAL → MIXED)
- [ ] `RbacIntegrationTest`:
  - User without permission → 403
  - User with permission → 200
  - Permission revoked → JWT expired → new login → 403
- [ ] `CredentialsInvalidationIntegrationTest`:
  - Issue JWT → change password → use old JWT → 401
  - Issue JWT → admin force-reauth → use old JWT → 401
  - Issue JWT → admin disable user → use old JWT → 401

---

### Phase 14.5 — Security Tests

**Implementation Tasks:**

- [ ] `JwtPayloadSecurityTest`:
  - JWT `sub` claim is UUID format (never contains `@`)
  - JWT `authorities` claim has no PII fields (no email, name, CPF)
  - JWT does not contain `password`, `hash`, `email`, `name` claims
  - Token size < 4 KB for typical permission set
- [ ] `AntiEnumerationTest`:
  - `POST /register` with existing email → same 201 response shape as new registration
  - `POST /login` with non-existent email → same 401 body as wrong password
  - `POST /login` with locked account → same 401 body as wrong password
  - `POST /password-reset/request` with non-existent email → 200
  - `GET /auth/email/verify` with invalid token → generic error (no email in message)
- [ ] `SecurityHeadersTest` — all required headers present on all responses
- [ ] `CsrfProtectionTest` — `POST` without CSRF token accepted (stateless JWT)
- [ ] `CorsTest` — wildcard origin rejected; allowed origin accepted
- [ ] `AuthorizationMatrixTest`:
  - Matrix of all sensitive endpoints × all permission strings
  - Each combination asserts correct HTTP status (200 vs 403)
- [ ] `SensitiveFieldLeakTest`:
  - All API response bodies parsed; assert no field named `password`, `hash`, `token`, `secret`
  - 500 error response contains no stack trace

---

### Phase 14.6 — Flyway Migration Validation Tests

**Implementation Tasks:**

- [ ] `FlywayMigrationTest`:
  - All migrations apply cleanly on fresh PostgreSQL 18 container
  - Migration count matches expected count
  - Checksums verified (no tampering)
- [ ] `SeedDataVerificationTest` — all expected seed slugs present post-migration
- [ ] `SchemaConstraintTest` — insert duplicate email → `DataIntegrityViolationException`; insert duplicate role name → exception
- [ ] `IndexPresenceTest` — verify critical indexes exist via `pg_indexes` query

---

