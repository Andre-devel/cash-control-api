# java-auth-template

Production-ready authentication and authorization module built with Spring Boot 4. Provides JWT-based stateless auth, role-based access control (RBAC), OAuth2 social login, brute-force protection, comprehensive audit logging, and LGPD-aligned privacy controls — all wired to PostgreSQL via Flyway-managed migrations.

---

## Table of Contents

1. [Technology Stack](#technology-stack)
2. [Architecture Overview](#architecture-overview)
3. [Authentication](#authentication)
   - [JWT — How It Works](#jwt--how-it-works)
   - [Registration](#registration-emailpassword)
   - [Email Verification](#email-verification)
   - [Login](#login-emailpassword)
   - [Google OAuth2](#google-oauth2)
   - [Logout](#logout)
   - [Password Reset](#password-reset)
   - [Password Change](#password-change-authenticated)
   - [Email Change](#email-change)
4. [Authorization — Roles & Permissions](#authorization--roles--permissions)
5. [Security Mechanisms](#security-mechanisms)
   - [Brute Force Protection](#brute-force-protection)
   - [Rate Limiting](#rate-limiting)
   - [HTTP Security Headers](#http-security-headers)
   - [CORS](#cors)
   - [Credential Invalidation](#credential-invalidation)
6. [Audit Logging](#audit-logging)
7. [Privacy & LGPD](#privacy--lgpd)
8. [Database Schema](#database-schema)
9. [API Reference](#api-reference)
10. [Configuration Reference](#configuration-reference)
11. [Running Locally](#running-locally)
12. [Docker](#docker)
13. [Testing](#testing)
14. [Project Structure](#project-structure)

---

## Technology Stack

| Component | Version | Role |
|-----------|---------|------|
| Java | 25 | Language (Virtual Threads enabled) |
| Spring Boot | 4.0.6 | Application framework |
| Spring Security | 7.x | Authentication & authorization |
| Spring Data JPA | (via Boot) | ORM / repository layer |
| PostgreSQL | 18 | Primary database |
| Flyway | (via Boot) | Schema migrations |
| JJWT | 0.12.6 | JWT signing & validation (HS512) |
| Spring OAuth2 JOSE | 7.x | OAuth2 client |
| Argon2 (Bouncy Castle 1.80) | 1.80 | Password hashing |
| Springdoc OpenAPI | 2.8.8 | Swagger UI / OpenAPI 3 |
| Logstash Logback Encoder | 8.0 | Structured JSON logging |
| Gradle | 8.13+ | Build tool |
| TestContainers | 1.21.0 | Integration tests with real PostgreSQL |

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                        Client                           │
│            (SPA / Mobile / Server-to-Server)            │
└───────────────────────┬─────────────────────────────────┘
                        │  HTTP + Bearer JWT
                        ▼
┌─────────────────────────────────────────────────────────┐
│                   Filter Chain                          │
│  RateLimitingFilter → JwtAuthenticationFilter           │
│           → CorrelationIdFilter                         │
└───────────────────────┬─────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────┐
│               Controllers (REST API)                    │
│  Auth │ User │ Role │ Permission │ Audit │ Admin        │
└───────────────────────┬─────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────┐
│                    Services                             │
│  AuthService │ UserService │ JwtService │ AuditService  │
│  BruteForceProtection │ PasswordReset │ EmailVerification│
└───────────────────────┬─────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────┐
│               Repositories (Spring Data JPA)            │
└───────────────────────┬─────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────┐
│               PostgreSQL 18                             │
│  users │ roles │ permissions │ audit_logs │ tokens …    │
└─────────────────────────────────────────────────────────┘
```

**Key design decisions:**

- **Stateless** — no server-side HTTP session. Every request is authenticated by verifying the JWT.
- **No refresh tokens** — short-lived access tokens (default 15 min). Re-login for a new token.
- **Credential invalidation without a blacklist** — `credentials_updated_at` column on `users`. Any JWT issued before this timestamp is rejected.
- **Database-driven lookups** — statuses, event types, and categories are FK rows, not SQL ENUMs. Extensible without DDL changes.

---

## Authentication

### JWT — How It Works

#### Issuance

A JWT is issued on successful login or OAuth2 callback. The token is returned in the response body as `{ "token": "..." }`. The client is responsible for storing and sending it.

```
POST /api/v1/auth/login  →  { "token": "<jwt>" }
```

#### Token Structure

```json
Header: { "alg": "HS512" }

Payload: {
  "sub":         "<user UUID>",
  "authorities": ["user:read", "role:create", ...],
  "token_type":  "access",
  "iat":         1716400000,
  "exp":         1716400900
}
```

| Claim | Description |
|-------|-------------|
| `sub` | User UUID — stable identity |
| `authorities` | Effective permissions at issuance time (union of all roles + direct permissions) |
| `token_type` | Always `"access"` (reserved for future token type distinctions) |
| `iat` | Issued-at (Unix epoch seconds) |
| `exp` | Expiry (default: `iat + 15 min`) |

#### Algorithm & Key

- **Algorithm:** HS512 — HMAC-SHA-512, symmetric.
- **Signing key:** loaded from the `JWT_SECRET` environment variable. Minimum 64 characters (512 bits) required.
- **Key is never transmitted** — it lives only on the server.

#### Validation (every authenticated request)

`JwtAuthenticationFilter` runs before the controller on every request:

1. Extracts `Authorization: Bearer <token>` header.
2. Parses and verifies the **HMAC-SHA-512 signature** using `JWT_SECRET`.
3. Checks **expiration** (`exp` claim).
4. Loads the user from the database to read `credentials_updated_at`.
5. Compares `iat` against `credentials_updated_at` — if the token was issued **before** the last credential update, it is **rejected** (forces re-login after password reset, admin force-reauth, etc.).
6. Extracts `authorities` from the token and maps them to Spring `GrantedAuthority` objects — no further DB call needed for authorization.

```
Request
  └─► verify signature     → 401 if invalid
  └─► check exp            → 401 if expired
  └─► load credentials_updated_at from DB
  └─► iat >= credentials_updated_at?  → 401 if token predates last credential change
  └─► set SecurityContext (authorities from token)
  └─► proceed to controller
```

#### Sending the Token

Include it as a standard Bearer token:

```
Authorization: Bearer eyJhbGciOiJIUzUxMiJ9...
```

> **Cookie / HttpOnly note:** This API does **not** use HttpOnly cookies for the JWT. The token is returned in the response body and the client stores it (memory or `localStorage`). This enables use from native apps, CLIs, and cross-origin SPAs. If you need cookie-based delivery (BFF pattern), it must be added at the gateway layer or as a custom response strategy.

---

### Registration (Email/Password)

```
POST /api/v1/auth/register
Content-Type: application/json

{
  "email": "user@example.com",
  "password": "Str0ng!Pass",
  "consentAccepted": true
}
```

**Flow:**

1. Validates email format and uniqueness, password strength (via `PasswordPolicy`), and LGPD consent flag.
2. Creates the account with status `PENDING_VERIFICATION`.
3. Generates a UUID email verification token, **hashes** it (SHA-256), stores only the hash.
4. Sends the raw token to the user's email.
5. **Anti-enumeration:** if the email already exists, the same success response is returned. The existing account receives an "account already exists" email — the API never reveals whether an email is registered.
6. Records audit events: `USER_REGISTERED`, `CONSENT_ACCEPTED`.

---

### Email Verification

```
GET /api/v1/auth/email/verify?token=<raw-token>
```

1. Hashes the incoming token and looks up the hash in `email_verification_tokens`.
2. Validates: exists, not expired, not consumed, not invalidated.
3. Sets user status to `ACTIVE`, records `email_verified_at`.
4. Marks the token as consumed (single-use).
5. Records audit event: `EMAIL_VERIFIED`.

To resend:

```
POST /api/v1/auth/email/verify/resend
{ "email": "user@example.com" }
```

---

### Login (Email/Password)

```
POST /api/v1/auth/login
Content-Type: application/json

{
  "email": "user@example.com",
  "password": "Str0ng!Pass"
}
```

**Flow:**

1. Checks user exists (generic 401 if not — no enumeration).
2. Checks account is not locked; auto-unlocks if `lockout_expires_at` has passed.
3. Checks account status is `ACTIVE` (rejects `PENDING_VERIFICATION`, `INACTIVE`, `LOCKED`).
4. Verifies password against **Argon2** hash.
5. On **failure:** increments `failed_login_attempts`. On reaching `MAX_FAILED_ATTEMPTS` (default 5), locks the account for `LOCKOUT_DURATION_MINUTES` (default 15). Records `AUTH_FAILURE`, `ACCOUNT_LOCKED`.
6. On **success:**
   - Resets `failed_login_attempts`.
   - Updates `last_login_at`.
   - Resolves effective permissions via `PermissionResolver`.
   - Issues JWT.
   - Records `AUTH_SUCCESS`.
   - Returns `{ "token": "...", "expiresIn": 900 }`.

All failure cases return the **same generic** `401 Unauthorized` body to prevent enumeration.

---

### Google OAuth2

**Initiate:**

```
GET /oauth2/authorization/google
```

The browser is redirected to Google's consent screen. OAuth2 state is stored in a short-lived cookie.

**Callback (framework-managed):**

```
GET /login/oauth2/code/google?code=...&state=...
```

**Flow:**

1. Spring Security exchanges the code for an ID token.
2. Retrieves `email`, `name`, `provider_user_id` from the ID token.
3. Looks up the email in `users`:
   - **Not found:** Creates a new user with status `ACTIVE`, `auth_origin=GOOGLE`. Records `USER_REGISTERED_GOOGLE`.
   - **Found (LOCAL origin):** Links the Google account, sets `auth_origin=MIXED`. Records `ACCOUNT_LINKED_GOOGLE`.
   - **Found (already GOOGLE or MIXED):** Updates `last_used_at`.
4. Issues JWT.
5. Redirects to `OAUTH2_SUCCESS_REDIRECT_URL?token=<jwt>`.

**Unlink provider:**

```
DELETE /api/v1/auth/provider/google    (requires active password on account)
```

---

### Logout

```
POST /api/v1/auth/logout
Authorization: Bearer <token>
```

Records audit event `AUTH_LOGOUT` and returns 200. The server has **no token blacklist** — the JWT remains cryptographically valid until `exp`. Mitigation: short TTL (15 min default). The client must discard the token immediately.

---

### Password Reset

**Step 1 — Request:**

```
POST /api/v1/auth/password-reset/request
{ "email": "user@example.com" }
```

- Anti-enumeration: always returns 200 regardless of whether the email exists.
- Generates a UUID token, stores its hash in `password_reset_tokens` with expiry (default 60 min).
- Sends email with the raw token.
- Records `PASSWORD_RESET_REQUESTED`.

**Step 2 — Confirm:**

```
POST /api/v1/auth/password-reset/confirm
{
  "token": "<raw-token>",
  "newPassword": "NewStr0ng!Pass"
}
```

- Validates token (exists, not expired, not consumed).
- Validates new password strength.
- Hashes and updates `password_hash`.
- Updates `credentials_updated_at` → **all previously issued JWTs are immediately invalidated**.
- Marks token consumed.
- Records `PASSWORD_RESET_COMPLETED`, `CREDENTIALS_INVALIDATED`.

---

### Password Change (Authenticated)

```
POST /api/v1/auth/password/change
Authorization: Bearer <token>

{
  "currentPassword": "OldPass1!",
  "newPassword": "NewPass2!"
}
```

- Verifies `currentPassword` against stored hash.
- Ensures `newPassword != currentPassword`.
- Updates `password_hash` and `credentials_updated_at` → invalidates all prior JWTs.
- Records `PASSWORD_CHANGED`, `CREDENTIALS_INVALIDATED`.

---

### Email Change

**Step 1 — Initiate (authenticated):**

```
POST /api/v1/auth/email/change
Authorization: Bearer <token>
{ "newEmail": "new@example.com" }
```

Generates a verification token for `new@example.com`, sends it there. `users.email` is **not changed yet**.

**Step 2 — Verify:**

```
GET /api/v1/auth/email/verify?token=<raw-token>
```

Same flow as email verification, but `email_verification_tokens.new_email` is populated — after validation, `users.email` is updated to `new_email`.

---

## Authorization — Roles & Permissions

### Model

```
User ──< user_roles >── Role ──< role_permissions >── Permission
 └──< user_permissions >── Permission  (direct overrides)
```

### Permission Naming (resource:action)

| Permission | Scope |
|-----------|-------|
| `user:create` | Create users (admin) |
| `user:read` | Read user profiles |
| `user:update` | Update user status |
| `user:delete` | Soft-delete users |
| `role:create` | Create roles |
| `role:update` | Update roles / assign permissions to roles |
| `role:delete` | Delete roles |
| `role:assign` | Assign roles to users |
| `role:revoke` | Remove roles from users |
| `permission:grant` | Grant direct permissions to users |
| `permission:revoke` | Revoke direct permissions |
| `audit:view` | Read audit logs |
| `auth:manage` | Lock/unlock accounts, force re-auth |

### Permission Resolution

At login time, `PermissionResolver.resolveEffectivePermissions(userId)` computes the **union** of:

1. All permissions inherited from the user's active roles.
2. All direct permissions assigned to the user.

This flat list is embedded in the JWT `authorities` claim. No database call is made during authorization checks — the JWT is self-contained.

### Enforcement

- **HTTP level:** Public paths are explicitly whitelisted in `SecurityConfig`; everything else requires a valid JWT.
- **Method level:** `@PreAuthorize("hasAuthority('role:create')")` on controller methods.

---

## Security Mechanisms

### Brute Force Protection

Per-account lockout managed by `BruteForceProtectionService`:

| Parameter | Default | Env var |
|-----------|---------|---------|
| Max failed attempts before lock | 5 | `MAX_FAILED_ATTEMPTS` |
| Lockout duration | 15 min | `LOCKOUT_DURATION_MINUTES` |

On each failed login: `failed_login_attempts` incremented. On threshold: account status set to `LOCKED`, `lockout_expires_at` set. Lock is automatically lifted on the next login attempt after expiry. Admins can also unlock manually via `POST /api/v1/admin/security/users/{userId}/unlock`.

### Rate Limiting

IP-based sliding window, applied **before** authentication:

| Parameter | Default | Env var |
|-----------|---------|---------|
| Max requests / window | 20 | `RATE_LIMIT_REQUESTS_PER_MINUTE` |
| Window size | 60 s | `RATE_LIMIT_WINDOW_SECONDS` |

Protected paths:

- `POST /api/v1/auth/login`
- `POST /api/v1/auth/register`
- `POST /api/v1/auth/password-reset/request`
- `POST /api/v1/auth/email/verify/resend`

Returns `HTTP 429 Too Many Requests` with a `Retry-After` header when exceeded.

### HTTP Security Headers

Set by Spring Security:

| Header | Value |
|--------|-------|
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `DENY` |
| `Content-Security-Policy` | `default-src 'none'; frame-ancestors 'none'` |
| `Referrer-Policy` | `strict-origin-when-cross-origin` |

### CORS

| Parameter | Default | Env var |
|-----------|---------|---------|
| Allowed origins | `http://localhost:3000,http://localhost:8080` | `ALLOWED_ORIGINS` |
| Allowed methods | `GET,POST,PUT,DELETE,OPTIONS` | — |
| Allowed headers | `Authorization, Content-Type, X-Correlation-Id` | — |
| Credentials | `false` (JWT via header, not cookies) | — |
| Max age | `3600 s` | — |

No wildcard origins in production. Credentials are `false` because the JWT is sent as a Bearer header, not a cookie.

### CSRF

CSRF protection is **disabled**. Rationale: the API is stateless (no session cookies), uses JWT via the `Authorization` header, and is consumed by non-browser clients. The `Authorization` header cannot be set by cross-origin HTML forms, so CSRF is not a threat vector here.

### Credential Invalidation

When a user changes their password, resets it, or an admin calls force-reauth, `users.credentials_updated_at` is updated to `NOW()`.

The `JwtAuthenticationFilter` compares the JWT's `iat` against this value:

```
if (jwt.iat < user.credentials_updated_at)  →  reject (401)
```

This achieves **immediate invalidation** of all previously issued tokens with **zero infrastructure overhead** (no Redis, no blacklist table, no token revocation list).

---

## Audit Logging

All security-relevant events are recorded to `audit_logs` — an **append-only** table with no update or delete endpoints.

### Tracked Events

| Event | Category | Severity |
|-------|----------|----------|
| `USER_REGISTERED` | AUTHENTICATION | NORMAL |
| `USER_REGISTERED_GOOGLE` | AUTHENTICATION | NORMAL |
| `ACCOUNT_LINKED_GOOGLE` | AUTHENTICATION | NORMAL |
| `AUTH_SUCCESS` | AUTHENTICATION | NORMAL |
| `AUTH_FAILURE` | AUTHENTICATION | NORMAL |
| `AUTH_LOGOUT` | AUTHENTICATION | NORMAL |
| `EMAIL_VERIFIED` | ACCOUNT | NORMAL |
| `PASSWORD_CHANGED` | ACCOUNT | HIGH |
| `PASSWORD_RESET_REQUESTED` | ACCOUNT | NORMAL |
| `PASSWORD_RESET_COMPLETED` | ACCOUNT | HIGH |
| `CREDENTIALS_INVALIDATED` | TOKEN | HIGH |
| `ACCOUNT_LOCKED` | SECURITY | HIGH |
| `ACCOUNT_UNLOCKED` | SECURITY | NORMAL |
| `PERMISSION_GRANTED` | AUTHORIZATION | NORMAL |
| `PERMISSION_REVOKED` | AUTHORIZATION | NORMAL |
| `ROLE_ASSIGNED` | AUTHORIZATION | NORMAL |
| `ROLE_REMOVED` | AUTHORIZATION | NORMAL |
| `CONSENT_ACCEPTED` | PRIVACY | NORMAL |

### Record Structure

```
audit_logs
├── id                  UUID PK
├── event_type_id       FK → audit_event_types
├── outcome_id          FK → audit_outcomes (SUCCESS | FAILURE)
├── actor_user_id       FK → users (nullable — null for unauthenticated actions)
├── target_user_id      FK → users (nullable)
├── ip_address_masked   VARCHAR(45) — last octet/segment zeroed
├── user_agent_truncated VARCHAR(512)
├── correlation_id      UUID — per-request tracing via X-Correlation-Id header
├── metadata            JSONB — sanitized event context
└── created_at          TIMESTAMPTZ — immutable
```

### Data Sanitization

Before any data reaches `audit_logs`:

- Passwords: **never** logged.
- IP addresses: masked (`192.168.1.0` — last IPv4 octet zeroed; last 80 bits zeroed for IPv6).
- Email: masked (`a***@example.com`).
- Tokens: **never** logged (only hashes are stored in `*_tokens` tables).
- JWT payloads: not stored verbatim.
- JSONB metadata: validated by `AuditMetadataSanitizer` before persistence.

### Querying

```
GET /api/v1/audit                      (requires audit:view)
GET /api/v1/audit/users/{userId}       (requires audit:view)
```

Paginated and filterable by event type, date range, user, outcome.

---

## Privacy & LGPD

- **Consent required** at registration (`consentAccepted: true`). Stored in `user_consents` with timestamp, IP (masked), and version.
- **Soft-delete:** `users.deleted_at` set instead of physical deletion.
- **Anonymization:** `users.anonymized_at` — PII fields zeroed out on request.
- **Data minimization:** only `email`, `password_hash`, and `display_name` stored on core user record.
- **Masked IP:** logged IP addresses have the identifying segment zeroed.
- **Token security:** only hashed tokens stored; originals are single-use and time-limited.

---

## Database Schema

Managed by Flyway (`src/main/resources/db/migration/`, V1–V8+).

### Lookup Tables (seeded)

```
account_statuses     — ACTIVE, INACTIVE, LOCKED, PENDING_VERIFICATION
auth_origins         — LOCAL, GOOGLE, MIXED
oauth_providers      — GOOGLE
lockout_types        — AUTOMATIC, MANUAL
permission_categories— USER_MANAGEMENT, ROLE_MANAGEMENT, AUDIT, AUTH_MANAGEMENT, PERMISSION_MANAGEMENT
authentication_methods — PASSWORD, GOOGLE_OAUTH2, MFA_TOTP
audit_event_types    — (slug, category, severity) for each tracked event
audit_outcomes       — SUCCESS, FAILURE
```

### Core Tables

```sql
users (
  id                    UUID PK,
  email                 VARCHAR UNIQUE NOT NULL,
  password_hash         TEXT,
  display_name          VARCHAR,
  account_status_id     FK → account_statuses,
  auth_origin_id        FK → auth_origins,
  email_verified_at     TIMESTAMPTZ,
  failed_login_attempts INT DEFAULT 0,
  lockout_expires_at    TIMESTAMPTZ,
  lockout_type_id       FK → lockout_types,
  last_login_at         TIMESTAMPTZ,
  credentials_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  consent_accepted_at   TIMESTAMPTZ,
  consent_version       VARCHAR,
  deleted_at            TIMESTAMPTZ,   -- soft-delete
  anonymized_at         TIMESTAMPTZ,
  created_at            TIMESTAMPTZ NOT NULL,
  updated_at            TIMESTAMPTZ NOT NULL
)

roles (id, name UNIQUE, description, is_system_role, is_active, created_by_id FK, ...)
permissions (id, name UNIQUE, description, category_id FK, is_system_perm, is_active, ...)
role_permissions (role_id, permission_id, granted_by_id, granted_at)
user_roles (user_id, role_id, granted_by_id, granted_at, expires_at)
user_permissions (user_id, permission_id, granted_by_id, granted_at, expires_at)

email_verification_tokens (user_id, token_hash UNIQUE, new_email, expires_at, consumed_at, invalidated_at, ...)
password_reset_tokens (user_id, token_hash UNIQUE, expires_at, consumed_at, invalidated_at, ip_address_masked, ...)

oauth_accounts (user_id, provider_id FK, provider_user_id, provider_email, display_name, linked_at, ...)

login_attempts (user_id, auth_method_id, ip_address_masked, user_agent_truncated,
                was_successful, correlation_id, attempted_at)   -- append-only

account_lockouts (user_id, lockout_type_id, reason, locked_by_id, locked_at, expires_at, unlocked_at, ...)

audit_logs (...)  -- described above

user_consents (user_id, consent_version, accepted_at, ip_address_masked, revoked_at, ...)

mfa_configurations (user_id, method_id, secret_hash, is_enabled, ...)  -- future / inactive
```

**Design notes:**

- All PKs are UUIDs.
- No SQL `ENUM` types — all categories are FK rows for extensibility.
- `audit_logs` and `login_attempts` have no `updated_at` (append-only).
- Unique indices named `uidx_*`, composite indices named `idx_*`.
- `TIMESTAMPTZ` used throughout (UTC-aware).

---

## API Reference

Base path: `/api/v1`

### Auth — `/api/v1/auth`

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| POST | `/register` | — | Register (email/password) |
| POST | `/login` | — | Login; returns JWT |
| POST | `/logout` | ✓ | Record logout |
| GET | `/me` | ✓ | Authenticated user profile |
| POST | `/password/change` | ✓ | Change password |
| POST | `/password-reset/request` | — | Request password reset link |
| POST | `/password-reset/confirm` | — | Confirm reset with token |
| GET | `/email/verify` | — | Verify email with token |
| POST | `/email/verify/resend` | — | Resend verification email |
| POST | `/email/change` | ✓ | Initiate email change |
| DELETE | `/provider/{providerSlug}` | ✓ | Unlink OAuth2 provider |

### Users — `/api/v1/users`

| Method | Path | Auth | Requires | Description |
|--------|------|:----:|----------|-------------|
| GET | `/{userId}` | ✓ | — | Get user by ID |
| GET | `/` | ✓ | — | List users (paginated) |
| POST | `/` | ✓ | `user:create` | Admin create user |
| PUT | `/{userId}/profile` | ✓ | — | Update own profile |
| PUT | `/{userId}/status` | ✓ | `user:update` | Update user status |
| DELETE | `/{userId}` | ✓ | `user:delete` | Soft-delete user |

### Roles — `/api/v1/roles`

| Method | Path | Auth | Requires | Description |
|--------|------|:----:|----------|-------------|
| POST | `/` | ✓ | `role:create` | Create role |
| GET | `/` | ✓ | `role:create` or `role:update` | List roles |
| GET | `/{roleId}` | ✓ | `role:create` or `role:update` | Get role |
| PUT | `/{roleId}` | ✓ | `role:update` | Update role |
| DELETE | `/{roleId}` | ✓ | `role:delete` | Delete role |
| POST | `/{roleId}/permissions` | ✓ | `role:update` | Add permission to role |
| DELETE | `/{roleId}/permissions/{permissionId}` | ✓ | `role:update` | Remove permission from role |

### Permissions — `/api/v1/permissions`

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| GET | `/` | ✓ | List permissions |
| GET | `/{permissionId}` | ✓ | Get permission |

### User Roles — `/api/v1/users/{userId}/roles`

| Method | Path | Auth | Requires | Description |
|--------|------|:----:|----------|-------------|
| GET | `/` | ✓ | — | List user's roles |
| POST | `/` | ✓ | `role:assign` | Assign role to user |
| DELETE | `/{roleId}` | ✓ | `role:revoke` | Remove role from user |

### User Permissions — `/api/v1/users/{userId}/permissions`

| Method | Path | Auth | Requires | Description |
|--------|------|:----:|----------|-------------|
| GET | `/` | ✓ | — | List user's direct permissions |
| POST | `/` | ✓ | `permission:grant` | Grant direct permission |
| DELETE | `/{permissionId}` | ✓ | `permission:revoke` | Revoke direct permission |

### Audit — `/api/v1/audit`

| Method | Path | Auth | Requires | Description |
|--------|------|:----:|----------|-------------|
| GET | `/` | ✓ | `audit:view` | Query audit logs (paginated) |
| GET | `/users/{userId}` | ✓ | `audit:view` | User audit timeline |

### Admin Security — `/api/v1/admin/security`

| Method | Path | Auth | Requires | Description |
|--------|------|:----:|----------|-------------|
| POST | `/users/{userId}/lock` | ✓ | `auth:manage` | Manually lock account |
| POST | `/users/{userId}/unlock` | ✓ | `auth:manage` | Unlock account |
| POST | `/users/{userId}/force-reauth` | ✓ | `auth:manage` | Force re-authentication |
| GET | `/summary` | ✓ | `auth:manage` or `audit:view` | Security summary |

### OAuth2 (Spring-managed)

| Path | Description |
|------|-------------|
| `GET /oauth2/authorization/google` | Initiate Google OAuth2 |
| `GET /login/oauth2/code/google` | Callback (handled by framework) |

### Health

| Path | Description |
|------|-------------|
| `GET /actuator/health` | Liveness probe |
| `GET /actuator/health/liveness` | Kubernetes liveness |
| `GET /actuator/health/readiness` | Kubernetes readiness (DB check) |

### OpenAPI / Swagger

Available at `GET /swagger-ui.html` and `GET /v3/api-docs` (Springdoc OpenAPI).

---

## Configuration Reference

All configuration is environment-variable driven. No secrets are hardcoded.

| Variable | Required | Default | Description |
|----------|:--------:|---------|-------------|
| `DB_URL` | ✓ | — | JDBC URL (e.g., `jdbc:postgresql://localhost:5432/auth`) |
| `DB_USERNAME` | ✓ | — | PostgreSQL username |
| `DB_PASSWORD` | ✓ | — | PostgreSQL password |
| `JWT_SECRET` | ✓ | — | HS512 signing key — **minimum 64 characters** |
| `JWT_EXPIRATION_MINUTES` | — | `15` | Access token TTL in minutes |
| `MAX_FAILED_ATTEMPTS` | — | `5` | Failed logins before lockout |
| `LOCKOUT_DURATION_MINUTES` | — | `15` | Account lockout duration |
| `PASSWORD_RESET_EXPIRY_MINUTES` | — | `60` | Password reset token TTL |
| `EMAIL_VERIFICATION_EXPIRY_HOURS` | — | `24` | Email verification token TTL |
| `MAIL_HOST` | ✓ | — | SMTP server hostname |
| `MAIL_PORT` | — | `587` | SMTP port |
| `MAIL_USERNAME` | ✓ | — | SMTP username |
| `MAIL_PASSWORD` | ✓ | — | SMTP password |
| `GOOGLE_CLIENT_ID` | — | — | Google OAuth2 client ID (required for OAuth2) |
| `GOOGLE_CLIENT_SECRET` | — | — | Google OAuth2 client secret |
| `APP_BASE_URL` | — | `http://localhost:8080` | Base URL for email links |
| `OAUTH2_SUCCESS_REDIRECT_URL` | — | — | Redirect after successful OAuth2 login |
| `OAUTH2_FAILURE_REDIRECT_URL` | — | — | Redirect after failed OAuth2 login |
| `ALLOWED_ORIGINS` | — | `http://localhost:3000,...` | Comma-separated CORS allowed origins |
| `RATE_LIMIT_REQUESTS_PER_MINUTE` | — | `20` | Max requests per IP per window |
| `RATE_LIMIT_WINDOW_SECONDS` | — | `60` | Rate limit window size |
| `RETENTION_PASSWORD_RESET_DAYS` | — | `30` | Cleanup age for reset token records |
| `RETENTION_VERIFICATION_TOKEN_DAYS` | — | `7` | Cleanup age for verification token records |

Copy `.env.example` to `.env` to get started.

---

## Running Locally

**Prerequisites:** Java 25, PostgreSQL 18, Gradle 8.13+

```bash
# 1. Clone and configure
cp .env.example .env
# Edit .env — fill in DB_URL, DB_PASSWORD, JWT_SECRET, MAIL_*

# 2. Create the database
createdb auth

# 3. Run (Flyway migrations run automatically on startup)
./gradlew bootRun
```

The server starts on port `8080` by default.

**Dev profile** (`application-dev.yml`) enables SQL logging and DEBUG-level logs for Spring Security and the application package. Activate it with:

```bash
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun
```

---

## Docker

**Start everything (app + PostgreSQL 18):**

```bash
cp .env.example .env
# Edit .env

docker compose up -d
```

**Build image only:**

```bash
docker build -t java-auth-template .
```

**Image details:**

- Multi-stage build: Gradle builder on `eclipse-temurin:25-jdk-alpine`, runtime on `eclipse-temurin:25-jre-alpine`.
- Runs as a non-root user (`appuser`).
- Exposes port `8080`.
- Health check configured at `/actuator/health`.

**Kubernetes probes:**

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
```

---

## Testing

Integration tests use TestContainers — a real PostgreSQL 18 instance is spun up per test run. No mocking of the database layer.

```bash
./gradlew test
```

Test configuration: `src/test/resources/application-test.yml`

---

## Project Structure

```
src/main/java/com/example/auth/
├── AuthApplication.java
├── audit/                        # Audit infrastructure (slugs, sanitizer)
├── config/
│   ├── AppProperties.java        # Typed config bindings (@ConfigurationProperties)
│   ├── AsyncConfig.java          # Virtual-thread executor
│   ├── PasswordEncoderConfig.java# Argon2 bean
│   └── SecurityConfig.java       # Spring Security filter chain, CORS, OAuth2
├── controller/
│   ├── AdminSecurityController.java
│   ├── AuditController.java
│   ├── AuthController.java
│   ├── GlobalExceptionHandler.java
│   ├── PermissionController.java
│   ├── RoleController.java
│   ├── UserController.java
│   ├── UserPermissionController.java
│   └── UserRoleController.java
├── domain/
│   ├── entity/                   # JPA entities
│   └── exception/                # Domain exceptions
├── dto/
│   ├── request/                  # Validated request DTOs
│   └── response/                 # Response DTOs
├── repository/                   # Spring Data JPA repositories
├── security/
│   ├── JwtAuthenticationFilter.java
│   ├── JwtServiceImpl.java
│   ├── CorrelationIdFilter.java
│   ├── RateLimitingFilter.java
│   ├── PermissionResolver.java
│   ├── AuthenticatedUser.java    # @AuthenticationPrincipal type
│   └── oauth2/                   # OAuth2 handlers & cookie repository
├── service/
│   ├── AuthServiceImpl.java
│   ├── UserServiceImpl.java
│   ├── AuditService.java
│   ├── BruteForceProtectionService.java
│   ├── EmailVerificationService.java
│   ├── PasswordResetService.java
│   ├── SmtpEmailService.java
│   └── NoOpEmailService.java     # Dev/test no-op email
└── util/                         # DataMasker, TokenHasher, etc.

src/main/resources/
├── application.yml
├── application-dev.yml
└── db/migration/                 # Flyway scripts V1–V8+
```