● # Authentication Module — Technical & Architectural Specification

## Document Purpose

This document defines the technical and functional architecture of a production-ready
authentication module built on Spring Boot 4 and Java 25. It serves as an
enterprise-grade reusable foundation for modern Java REST API backends.

This document covers architecture, design decisions, security posture, and functional
behavior. It does not prescribe implementation steps, database migrations, code, or
delivery phases.

  ---

## 1. System Overview

### 1.1 Purpose

A self-contained, stateless authentication and authorization module designed for
single-tenant REST API applications, with architectural extensibility toward
multi-service and microservices environments.

### 1.2 Core Capabilities

| Capability                  | Description                                              |
  |-----------------------------|----------------------------------------------------------|
| Email/password authentication | Credential-based login with secure hashing             |
| Google OAuth2 login          | Social login with account auto-creation and linking     |
| Stateless JWT authentication | Short-lived signed access tokens, no server-side session|
| RBAC with granular permissions| Role and permission-based authorization                 |
| Brute force protection       | Failed attempt tracking and temporary lockout           |
| Password reset flow          | Secure time-limited token via email                     |
| Audit logging                | Structured event logging for all auth lifecycle events  |
| LGPD-aligned privacy design  | Minimal data collection, secure storage, no data leakage|

### 1.3 Architectural Posture

- **Stateless** by default: no server-side session for authentication.
- **Defense in depth**: multiple independent security layers.
- **Least privilege**: minimal JWT payload; server-enforced authorization.
- **Privacy-by-design**: LGPD-aligned from the ground up.
- **Cloud-native ready**: Docker/Kubernetes-friendly, environment-based configuration.

  ---

## 2. Technology Stack

| Layer                  | Technology                                      |
  |------------------------|-------------------------------------------------|
| Language               | Java 25                                         |
| Framework              | Spring Boot 4.0.6                               |
| Security               | Spring Security 7                               |
| Persistence            | Spring Data JPA / Hibernate, PostgreSQL 18      |
| Schema migrations      | Flyway                                          |
| Build tool             | Gradle                                          |
| Social login           | Spring Security OAuth2 Client                   |
| Token standard         | JWT (JSON Web Token)                            |
| Validation             | Bean Validation / Jakarta Validation            |
| Utilities              | Lombok                                          |
| Infrastructure         | Docker-ready architecture                       |

  ---

## 3. Authentication Architecture

### 3.1 Token Strategy

The system adopts a **pure stateless JWT authentication** model. Authentication state
lives entirely in the signed JWT access token — no server-side session, no token
persistence, no refresh token flow.

**Access Token**
- Short-lived JWT (recommended: 5–15 minutes).
- Signed with a strong asymmetric or symmetric algorithm (RS256 or HS512).
- Contains minimal claims: subject (user UUID), roles/authorities, token type,
  issued-at (`iat`), expiration (`exp`).
- Never contains sensitive personal data, passwords, or verbose profile information.
- Validated on every protected request without server-side lookup.
- When the token expires, the user must re-authenticate to obtain a new one.

### 3.2 Session Lifecycle

[Login]
→ Validate credentials
→ Issue Access Token (JWT, short TTL)
→ Return access token in response body

[Authenticated Request]
→ Validate Access Token signature + expiration (stateless)
→ Validate token iat against user's credentials_updated_at
→ Extract user context from JWT claims

[Token Expiry]
→ Client receives 401 Unauthorized
→ User re-authenticates to obtain a new access token

[Logout]
→ Client discards the local access token
→ Optional: client calls logout endpoint to record the audit event
→ No server-side session state is modified

### 3.3 JWT Invalidation Strategy

Because JWTs are stateless, they cannot be individually revoked before expiration.
The system uses a lightweight credential-version approach to handle forced invalidation:

- The `users` table contains a `credentials_updated_at` timestamp.
- This field is updated on: password change, password reset, account disable,
  and admin-triggered forced re-authentication.
- The JWT `iat` (issued-at) claim is validated against `credentials_updated_at` at
  token validation time.
- Any JWT issued before `credentials_updated_at` is rejected as invalid.
- This requires one lightweight DB read per authenticated request but remains
  architecturally stateless — no session table, no token revocation list.

  ---

## 4. User Model & Identity

### 4.1 User Entity

A `User` represents an authenticated principal in the system. It is designed for
minimal personal data exposure and supports both local and social identity origins.

**Core identity fields:**
- UUID as primary key.
- Unique email address.
- Hashed password (nullable for social-only accounts).
- Account status: `ACTIVE`, `INACTIVE`, `LOCKED`, `PENDING_VERIFICATION`.
- Email verification status and token.
- Google OAuth2 provider ID (nullable for local accounts).
- Authentication origin flag: `LOCAL`, `GOOGLE`, `MIXED`.
- Last login timestamp.
- Failed login attempt counter.
- Lockout expiration timestamp.
- `credentials_updated_at` timestamp for lightweight JWT invalidation.
- Standard audit timestamps: `createdAt`, `updatedAt`.

**Design constraints:**
- Password field is write-only by convention; never included in API responses or logs.
- Personal data fields are separated from authentication fields in the domain model.
- Soft-delete capable for LGPD compliance (account deactivation without data loss).

### 4.2 Password Security

- Passwords are hashed using **Argon2id** (preferred) or BCrypt (strong cost factor ≥12).
- Argon2id is the current OWASP recommendation for password hashing.
- Raw passwords are never logged, persisted in plaintext, or included in events.
- Password strength rules are enforced at the API layer via Jakarta Validation.

  ---

## 5. RBAC & Permissions

### 5.1 Model

The system implements a three-layer RBAC model:

User → has many Roles → each Role has many Permissions
User → may also have direct Permissions (optional, for fine-grained overrides)

### 5.2 Role

A named grouping of permissions representing a functional access level.
Examples: `ADMIN`, `MANAGER`, `VIEWER`, `SUPPORT`.

Roles are not hardcoded. They are data-driven and managed via API.

### 5.3 Permission

A fine-grained named capability following the `resource:action` convention.

**Built-in permission taxonomy (illustrative, not exhaustive):**

| Permission          | Meaning                               |
  |---------------------|---------------------------------------|
| `user:create`       | Create new users                      |
| `user:read`         | Read user data                        |
| `user:update`       | Update user profile                   |
| `user:delete`       | Delete/deactivate users               |
| `role:create`       | Create roles                          |
| `role:update`       | Modify role permissions               |
| `role:delete`       | Remove roles                          |
| `permission:grant`  | Assign permissions to roles/users     |
| `permission:revoke` | Remove permissions                    |
| `audit:view`        | Access audit log data                 |
| `auth:manage`       | Manage authentication, lockouts, forced re-authentication |

### 5.4 Authorization Enforcement

Authorization is **exclusively server-side**. The frontend must never be trusted as a
security boundary.

Enforcement mechanisms:
- **Spring Security filter chain**: request-level access control.
- **Method-level security** via `@PreAuthorize` with Spring Security Expression Language.
- Permission checks use authority strings derived from the JWT `authorities` claim.
- The system checks for specific permission strings, not just roles, enabling
  fine-grained access control beyond coarse role assignments.

Example enforcement pattern:
@PreAuthorize("hasAuthority('user:delete')")
@PreAuthorize("hasAuthority('audit:view')")
@PreAuthorize("hasAnyAuthority('role:create', 'role:update')")

### 5.5 JWT Authority Claims

The JWT `authorities` claim carries the flattened set of effective permissions for the
authenticated user (union of all role permissions plus any direct permissions).

This claim is populated at token issuance time and does not change during token
validity — consistent with stateless design.

  ---

## 6. Core Authentication Workflows

### 6.1 Registration — Email/Password

1. Client submits email, password, and any required consent fields.
2. System validates: email format, email uniqueness, password strength policy.
3. Password is hashed with Argon2id.
4. User record is created in `PENDING_VERIFICATION` status.
5. An email verification token is generated (UUID, time-limited) and dispatched.
6. Audit event `USER_REGISTERED` is recorded.
7. Account is functional for authentication only after email verification.

**Anti-enumeration**: Registration responses must not distinguish between
"email already exists" and other errors in a way that enables email harvesting.
Use a uniform success-like response while dispatching the appropriate email action.

### 6.2 Registration — Google OAuth2

1. Client initiates the OAuth2 Authorization Code flow via Spring Security OAuth2 Client.
2. Google authenticates the user and returns an authorization code.
3. System exchanges code for tokens and retrieves Google profile (email, name, provider ID).
4. If the email does not exist: a new user is auto-created with `ACTIVE` status,
   email marked verified, and OAuth2 origin recorded.
5. If the email already exists with a local account: accounts are linked
   (`MIXED` origin), and the Google provider ID is associated.
6. A new JWT access token is issued.
7. Audit event `USER_REGISTERED_GOOGLE` or `ACCOUNT_LINKED_GOOGLE` is recorded.

### 6.3 Login — Email/Password

1. Client submits email and password.
2. System checks: user existence, account status, lockout state.
3. Password is verified against the stored hash.
4. On failure:
    - Failed attempt counter is incremented.
    - If threshold exceeded: account is temporarily locked until configurable expiry.
    - Audit event `AUTH_FAILURE` is recorded.
    - Generic error response is returned (no distinction between wrong email/password).
5. On success:
    - Failed attempt counter is reset.
    - Last login timestamp is updated.
    - JWT access token is issued with minimal claims: `sub` (UUID), `authorities`,
      `iat`, `exp`, `token_type`.
    - Audit event `AUTH_SUCCESS` is recorded.

### 6.4 Login — Google OAuth2

Follows the same OAuth2 flow as Google registration.
If the user already exists and is verified via Google, login proceeds directly
to token issuance without requiring password verification.

### 6.5 Logout

1. Client discards the local JWT access token.
2. Optionally, client calls the logout endpoint.
3. If the logout endpoint is called: audit event `AUTH_LOGOUT` is recorded.
4. No server-side session state is modified. The system is fully stateless.
5. The discarded token remains cryptographically valid until its natural expiration.
   The short TTL (5–15 minutes) bounds the residual exposure window.

### 6.6 Password Reset

1. Client submits email to initiate reset.
2. System locates user by email.
3. A cryptographically secure, single-use reset token (UUID or similar) is generated
   with a configurable short expiration (e.g., 30–60 minutes).
4. Reset token is stored (hashed) in the database, linked to the user.
5. Reset link is dispatched via email.
6. **Anti-enumeration**: response is always success-like, regardless of whether the
   email exists in the system.
7. Client submits the reset token and new password.
8. System validates: token exists, not expired, not already used.
9. New password is validated against strength policy and hashed.
10. Token is marked consumed; user password is updated.
11. `credentials_updated_at` is updated, invalidating all previously issued JWTs.
12. Audit events `PASSWORD_RESET_REQUESTED` and `PASSWORD_RESET_COMPLETED` are recorded.

  ---

## 7. Brute Force Protection

### 7.1 Per-Account Lockout

- Failed login attempts are counted per user account.
- On exceeding the threshold (configurable, e.g., 5 attempts): account is temporarily
  locked until a configurable expiry window (e.g., 15 minutes).
- Counter resets on successful authentication.
- Lockout status is persisted in the user record.

### 7.2 Rate Limiting

- Rate limiting is applied at the API gateway or filter level on authentication endpoints.
- IP-based and/or account-based rate limiting for `/auth/login`, `/auth/register`,
  and `/auth/password-reset`.
- Exceeding limits returns HTTP 429 with appropriate `Retry-After` header.

### 7.3 Generic Error Responses

- Authentication failures must never reveal whether the email exists, whether the
  password was wrong, or whether the account is locked.
- A single generic error message is returned for all authentication failure scenarios.
- This prevents user enumeration and account oracle attacks.

  ---

## 8. Audit & Security Events

### 8.1 Audited Events

| Event                       | Trigger                                                  |
  |-----------------------------|----------------------------------------------------------|
| `USER_REGISTERED`           | New local account created                                |
| `USER_REGISTERED_GOOGLE`    | New account via Google OAuth2                            |
| `ACCOUNT_LINKED_GOOGLE`     | Existing account linked to Google                        |
| `AUTH_SUCCESS`              | Successful login                                         |
| `AUTH_FAILURE`              | Failed login attempt                                     |
| `ACCOUNT_LOCKED`            | Account locked after failed attempts                     |
| `AUTH_LOGOUT`               | Explicit logout event recorded                           |
| `CREDENTIALS_INVALIDATED`   | credentials_updated_at updated; prior JWTs invalidated   |
| `PASSWORD_RESET_REQUESTED`  | Password reset initiated                                 |
| `PASSWORD_RESET_COMPLETED`  | Password reset finalized                                 |
| `PASSWORD_CHANGED`          | Password changed by authenticated user                   |
| `EMAIL_VERIFIED`            | Email verification completed                             |
| `PERMISSION_GRANTED`        | Permission assigned to role or user                      |
| `PERMISSION_REVOKED`        | Permission removed from role or user                     |
| `ROLE_ASSIGNED`             | Role assigned to user                                    |
| `ROLE_REMOVED`              | Role removed from user                                   |

### 8.2 Audit Record Structure

Each audit record contains:
- UUID primary key.
- Event type (enum).
- Actor user UUID (nullable for unauthenticated events).
- Target user UUID (nullable).
- IP address (masked/truncated for LGPD compliance).
- User-Agent string (truncated).
- Timestamp (UTC).
- Outcome: `SUCCESS` or `FAILURE`.
- Event-specific metadata (JSON, without sensitive fields).

### 8.3 Data Protection in Logs

- Passwords are **never** logged.
- Full IP addresses may be partially masked (e.g., last octet zeroed) per LGPD requirements.
- Email addresses in logs must be masked (e.g., `a***@example.com`).
- Token values are **never** logged.
- JWT payloads are **never** logged verbatim.

### 8.4 Log Structure

Logs are structured (JSON format recommended) for integration with observability stacks
(ELK, Loki, CloudWatch, etc.). Each log entry includes a correlation ID for distributed
tracing readiness.

  ---

## 9. Security Requirements

### 9.1 Cryptographic Standards

| Component           | Algorithm / Standard                                  |
  |---------------------|-------------------------------------------------------|
| Password hashing    | Argon2id (preferred) or BCrypt (cost ≥ 12)            |
| JWT signing         | RS256 (asymmetric) or HS512 (symmetric, env-managed)  |
| Password reset token| Cryptographically secure random, single-use           |

### 9.2 Transport Security

- HTTPS mandatory in all environments except local development.
- `Strict-Transport-Security` (HSTS) header enforced in production.
- TLS 1.2 minimum; TLS 1.3 preferred.

### 9.3 HTTP Security Headers

The following headers are enforced via Spring Security:

| Header                          | Value / Policy                          |
  |---------------------------------|-----------------------------------------|
| `X-Content-Type-Options`        | `nosniff`                               |
| `X-Frame-Options`               | `DENY`                                  |
| `Content-Security-Policy`       | Strict policy, no inline scripts        |
| `Strict-Transport-Security`     | `max-age=31536000; includeSubDomains`   |
| `Referrer-Policy`               | `strict-origin-when-cross-origin`       |
| `Permissions-Policy`            | Restrictive default                     |
| `Cache-Control`                 | `no-store` on auth endpoints            |

### 9.4 CORS Configuration

- CORS is explicitly configured (no wildcard `*` in production).
- Allowed origins are driven by environment configuration.
- Only the required HTTP methods and headers are permitted.

### 9.5 CSRF Considerations

- The system is fully stateless — no session cookies, no authentication cookies.
- JWT tokens are transmitted via the `Authorization: Bearer` header, which is not
  automatically attached by browsers, providing inherent CSRF protection.
- Spring Security CSRF protection is disabled for stateless JWT endpoints per
  Spring Security best practices for REST APIs.

### 9.6 Input Validation

- All incoming DTOs are validated via Jakarta Validation (`@Valid`, `@NotBlank`,
  `@Email`, `@Size`, custom constraints).
- Validation is enforced at the controller layer; invalid requests are rejected
  with `400 Bad Request` before reaching the service layer.
- No business logic executes on unvalidated input.

### 9.7 Secret Management

- JWT signing keys, OAuth2 client credentials, and database credentials are
  never hardcoded.
- All secrets are injected via environment variables or an external secrets manager.
- No secrets appear in version control, application properties committed to source,
  or logs.

  ---

## 10. Privacy & LGPD Requirements

### 10.1 Data Minimization

- Only data strictly necessary for authentication and authorization is collected.
- User profile data (full name, phone, etc.) is out of scope for this module.
- Google OAuth2 profile data collected at registration is limited to: email, name
  (display only), and provider ID.

### 10.2 Sensitive Data Handling

- Passwords are hashed and never stored or transmitted in plaintext.
- Password reset tokens are stored as hashes, not raw values.
- JWTs contain no sensitive personal data beyond the user's UUID and permissions.

### 10.3 JWT Privacy Constraints

The JWT payload **must not** contain:

- Passwords or password hashes.
- Full email address (subject claim uses UUID, not email).
- Phone numbers, CPF, or any government identifiers.
- Any data classified as sensitive under LGPD Article 11.
- Verbose role or organizational hierarchy data.
- Excessive user metadata.

### 10.4 Audit Log Privacy

- IP addresses are masked before persistence.
- Email addresses in audit records are masked.
- Token values are excluded from all logs.
- Audit records comply with configurable retention policies.

### 10.5 User Enumeration Prevention

- Authentication, registration, and password reset flows must not expose whether
  a given email address exists in the system.
- All responses on these flows return uniform messages regardless of user existence.

### 10.6 LGPD Future Readiness

The architecture must support, without fundamental redesign, the following future
capabilities:

| Future Capability               | Architecture Requirement                          |
  |---------------------------------|---------------------------------------------------|
| Account deletion / right to erasure | Soft-delete + anonymization pipeline        |
| Data portability                | Exportable user data structure                    |
| Consent management              | Consent flag in user record, consent event log    |
| Privacy request workflows       | Request tracking linked to user identity          |
| Compliance auditing             | Audit trail of data access events                 |
| Log retention controls          | Configurable TTL on audit tables                  |

  ---

## 11. Persistence Architecture

### 11.1 Primary Entities

| Entity               | Purpose                                                      |
  |----------------------|--------------------------------------------------------------|
| `users`              | Core user identity, authentication state, credential version |
| `roles`              | Named role groupings                                         |
| `permissions`        | Granular permission definitions                              |
| `user_roles`         | Many-to-many: user ↔ role                                    |
| `role_permissions`   | Many-to-many: role ↔ permission                              |
| `user_permissions`   | Optional direct user-level permission overrides              |
| `password_reset_tokens` | Time-limited, single-use reset tokens                    |
| `audit_events`       | Structured security and auth audit log                       |

### 11.2 Entity Design Constraints

- **UUID** primary keys on all entities.
- `created_at` and `updated_at` timestamps, auto-managed by JPA/Hibernate.
- `credentials_updated_at` on `users` enables lightweight JWT invalidation without
  session persistence.
- Password reset tokens have `expires_at` and `consumed_at` timestamps.
- Soft-delete support on `users` via `deleted_at` nullable timestamp.
- Appropriate unique constraints: email on users, permission name, role name.
- Foreign key constraints enforced at the database level.
- Indexes on: user email, reset token hash, audit event user UUID and timestamp.

### 11.3 Schema Management

- All schema changes managed exclusively via Flyway versioned migrations.
- No schema changes via Hibernate `ddl-auto` in production environments.
- Flyway is configured to run automatically on application startup in controlled
  environments only.

  ---

## 12. Architectural Patterns & Design Principles

### 12.1 Layered Architecture

Controller (API) Layer
→ Receives HTTP requests, validates DTOs, delegates to service
→ Returns standardized API responses

Service Layer
→ Encapsulates all business and authentication logic
→ Orchestrates domain objects and repository calls
→ Throws domain-specific exceptions

Repository Layer
→ Spring Data JPA repositories
→ No business logic; data access only

Domain Layer
→ JPA entities and value objects
→ Domain exceptions

Security Layer
→ Spring Security filter chain
→ JWT authentication filter
→ Custom UserDetailsService
→ Method-level authorization

### 12.2 Design Principles

- **Single Responsibility**: each class has one clear reason to change.
- **Open/Closed**: security configuration and auth strategies are extensible without
  modifying core logic.
- **Dependency Inversion**: services depend on interfaces, not concrete implementations.
- **DTO pattern**: request/response objects are strictly separated from JPA entities.
  Entities never leak outside the service layer.
- **Centralized exception handling**: a global `@ControllerAdvice` maps domain
  exceptions to appropriate HTTP responses uniformly.
- **Environment-based configuration**: `application.yml` uses environment variable
  binding; no secrets in default profiles.

### 12.3 API Response Conventions

- Consistent envelope structure for all API responses.
- Errors return a standardized error body with: error code, generic message,
  and correlation ID.
- HTTP status codes follow REST conventions strictly.
- Authentication failures: `401 Unauthorized`; authorization failures: `403 Forbidden`.
- Validation errors: `400 Bad Request` with field-level details.

### 12.4 Extensibility Points

The following extension points are deliberately designed into the architecture:

| Extension Point                | Future Use Case                                    |
  |--------------------------------|----------------------------------------------------|
| OAuth2 provider abstraction    | Adding GitHub, Microsoft, Apple login              |
| Token strategy interface       | Migrating to opaque tokens or PASETO               |
| Permission evaluator           | Attribute-based or resource-based access control   |
| Notification port              | Email, SMS, webhook adapters                       |
| Audit sink                     | Pluggable audit backend (DB, message broker, SIEM) |
| Multi-tenancy layer            | Tenant context injection into security chain       |

  ---

## 13. Non-Functional Requirements

| Requirement          | Target                                                              |
  |----------------------|---------------------------------------------------------------------|
| **Availability**     | Stateless design enables horizontal scaling with no affinity        |
| **Scalability**      | Shared-nothing architecture; no per-request DB hit for JWT validation |
| **Security posture** | OWASP Top 10 mitigations; ASVS Level 2 alignment                    |
| **Observability**    | Structured JSON logs; correlation IDs; audit event stream           |
| **Testability**      | Pure service layer, mockable ports, no static coupling              |
| **Deployability**    | Docker-ready; externalized configuration; health endpoints          |
| **Recoverability**   | credentials_updated_at enables forced re-authentication at any time |
| **Compliance**       | LGPD-aligned data minimization and audit trail                      |
| **Latency**          | Stateless JWT validation: no DB hit per authenticated request       |
| **Portability**      | No vendor-specific APIs; standard Spring ecosystem only             |

  ---

## 14. Security Threat Model Summary

| Threat                          | Mitigation                                               |
  |---------------------------------|----------------------------------------------------------|
| Credential brute force          | Account lockout, rate limiting, generic error messages   |
| Token theft (access)            | Short TTL (5–15 min); HTTPS-only; CSP headers            |
| XSS token theft                 | Short TTL; CSP headers; no sensitive data in JWT         |
| User enumeration                | Generic responses on all auth/reset flows                |
| Password exposure               | Argon2id hashing; never logged or returned               |
| JWT tampering                   | RS256/HS512 signature; validation on every request       |
| Insecure secrets                | Environment-based injection; no hardcoded values         |
| Privilege escalation            | Server-enforced permission checks; minimal JWT claims    |
| Session fixation                | Stateless design; new tokens issued on every login       |
| LGPD data exposure              | Masked logs; minimal JWT payload; soft-delete support    |
| Stale JWT after credential change | credentials_updated_at validation rejects stale tokens |