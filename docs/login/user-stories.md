# User Stories — Java Authentication & Authorization Module

## Overview

This document captures production-grade user stories for the java-auth-template authentication and authorization module — an enterprise-ready, self-contained Spring Boot 4 / Java 25 REST backend security foundation.

The module serves three categories of principals:

- **Anonymous Users**: unauthenticated actors interacting with public endpoints (registration, login, password reset).
- **Authenticated Users**: principals with valid JWT access tokens accessing protected resources.
- **Administrators / Security Operators**: principals with elevated roles or permissions managing identities, roles, permissions, and audit data.

All stories are MVP-first, implementation-aware, and aligned with stateless JWT authentication, Spring Security 7, Argon2id hashing, granular RBAC, and LGPD privacy-by-design requirements.

---

# 1. Authentication & Registration

## US-1.1: Email and Password Registration

**As an** anonymous user  
**I want to** register with my email address and a password  
**So that** I can create an account and gain access to the system

**Acceptance Criteria:**
- [ ] The system accepts a registration request containing at minimum: email address and password.
- [ ] Email is validated for correct format (RFC 5322); password is validated against the configured strength policy.
- [ ] If the email is already registered, the system returns a uniform success-like response and internally dispatches a "you already have an account" notification email — the conflict is never revealed to the caller.
- [ ] The password is hashed using Argon2id (or BCrypt with cost ≥ 12) before persistence.
- [ ] The user record is created with status `PENDING_VERIFICATION`.
- [ ] An email verification token (UUID, time-limited) is generated, stored hashed, and dispatched via email.
- [ ] The raw password is never logged, stored in plaintext, or included in any event payload.
- [ ] Audit event `USER_REGISTERED` is recorded with masked email, masked IP, and UTC timestamp.
- [ ] The response body never returns the hashed password, verification token, or internal tokens.
- [ ] The account cannot perform protected actions until email verification is completed.

**Expected Result:** A new user account is created in `PENDING_VERIFICATION` status. A verification email is dispatched. The response does not expose internal state or email existence.

---

## US-1.2: Email Verification

**As a** newly registered user  
**I want to** verify my email address by following the link in the verification email  
**So that** my account becomes active and I can authenticate

**Acceptance Criteria:**
- [ ] The verification endpoint accepts a time-limited token from the email link.
- [ ] The system validates: token exists in the database (by hash), is not expired, is not already consumed.
- [ ] On success: user status transitions from `PENDING_VERIFICATION` to `ACTIVE`; token is marked consumed.
- [ ] Audit event `EMAIL_VERIFIED` is recorded.
- [ ] On invalid or expired token: a generic error response is returned without revealing email existence or failure reason.
- [ ] The endpoint is safe to re-call on an already-verified account (idempotent, returns success).
- [ ] Verification tokens have a configurable TTL (e.g., 24 hours).
- [ ] After expiration, the user can request a new verification email via a resend flow.

**Expected Result:** The user's account is activated. Expired or invalid tokens return a safe generic error.

---

## US-1.3: Resend Email Verification

**As a** user in `PENDING_VERIFICATION` status  
**I want to** request a new verification email  
**So that** I can complete registration if the original link expired or was not received

**Acceptance Criteria:**
- [ ] The endpoint accepts an email address.
- [ ] If the email exists and account is in `PENDING_VERIFICATION`: the old token is invalidated, a new one is generated and dispatched.
- [ ] If the email does not exist or is already verified: the system returns a uniform success-like response (anti-enumeration).
- [ ] Rate limiting is applied to prevent email flooding.
- [ ] An audit event is recorded on resend.

**Expected Result:** A new verification email is sent where appropriate. The caller cannot determine email existence from the response.

---

## US-1.4: Email and Password Login

**As a** registered and email-verified user  
**I want to** log in with my email and password  
**So that** I receive a short-lived JWT access token to make authenticated requests

**Acceptance Criteria:**
- [ ] The system accepts email and password; both are validated before any authentication logic executes.
- [ ] The system checks user existence, account status, and lockout state before password verification.
- [ ] On any failure (wrong email, wrong password, locked, unverified): a single generic HTTP 401 is returned — the failure cause is never distinguished in the response.
- [ ] On failure: the failed attempt counter is incremented on the user record.
- [ ] On exceeding the configured threshold: account enters temporary lockout; `ACCOUNT_LOCKED` audit event is recorded.
- [ ] On success:
  - [ ] Failed attempt counter resets to zero; last login timestamp is updated.
  - [ ] JWT access token is issued with minimal claims: `sub` (UUID), `authorities`, `iat`, `exp`, `token_type`.
  - [ ] The JWT is returned in the response body.
- [ ] Audit event `AUTH_SUCCESS` or `AUTH_FAILURE` is recorded accordingly.
- [ ] `Cache-Control: no-store` header is present on the response.

**Expected Result:** On success, the user receives a short-lived signed JWT access token. On any failure, a generic 401 is returned with no information leak.

---

## US-1.5: Logout

**As an** authenticated user  
**I want to** log out  
**So that** my local session is terminated and the auth event is recorded

**Acceptance Criteria:**
- [ ] The logout endpoint optionally accepts a valid access token (used to record the audit event).
- [ ] The client is responsible for discarding the JWT access token locally.
- [ ] No server-side session state is modified — the system is fully stateless.
- [ ] If the logout endpoint is called: audit event `AUTH_LOGOUT` is recorded with masked IP and UTC timestamp.
- [ ] The previously issued JWT remains cryptographically valid until its natural expiration. The short TTL (5–15 minutes) bounds the residual exposure window.
- [ ] HTTP 200/204 is returned.

**Expected Result:** The client discards the JWT. The server records the logout event. No server-side session invalidation occurs; the short-lived token expires on schedule.

---

## US-1.6: JWT Token Expiry and Re-Authentication

**As an** authenticated user whose JWT access token has expired  
**I want to** receive a clear authentication failure and be prompted to log in again  
**So that** I understand the session has ended and can re-authenticate to continue

**Acceptance Criteria:**
- [ ] When a request is made with an expired JWT, the system returns HTTP 401 with a standardized error body.
- [ ] The error body includes a correlation ID; it does not include the raw JWT or expiration details.
- [ ] The client must initiate a full login flow to obtain a new JWT access token.
- [ ] No automatic token issuance occurs server-side — re-authentication is always explicit.
- [ ] The configured JWT TTL (5–15 minutes) is enforced via the `exp` claim; no server-side clock skew tolerance beyond a small configurable grace window.
- [ ] Audit event `AUTH_FAILURE` is not recorded for natural JWT expiry — the 401 is a stateless validation response, not a security event.

**Expected Result:** Expired tokens produce a 401. The user re-authenticates via the login flow. The system never silently extends a token's validity.

---

## US-1.7: Password Reset — Initiation

**As a** user who forgot their password  
**I want to** request a password reset email  
**So that** I can regain access to my account securely

**Acceptance Criteria:**
- [ ] The endpoint accepts an email address.
- [ ] If the email exists and is active: a cryptographically secure, single-use reset token is generated, hashed, persisted with a short TTL (e.g., 30–60 minutes), and dispatched via email. Any previously active reset token for the user is invalidated.
- [ ] If the email does not exist or is inactive: the system returns the same HTTP 200 success-like response — email existence is never revealed.
- [ ] Rate limiting is applied per IP and per email.
- [ ] Audit event `PASSWORD_RESET_REQUESTED` is recorded with masked email.

**Expected Result:** A reset email is dispatched to valid accounts. All callers receive a uniform response, preventing enumeration.

---

## US-1.8: Password Reset — Completion

**As a** user who received a password reset email  
**I want to** submit my reset token and a new password  
**So that** my credentials are updated and all previously issued JWTs are invalidated

**Acceptance Criteria:**
- [ ] The endpoint accepts a reset token and a new password.
- [ ] System validates: token exists (by hash), is not expired, is not already consumed.
- [ ] New password is validated against the strength policy and hashed with Argon2id.
- [ ] On success: reset token is marked consumed; new password hash is persisted.
- [ ] `credentials_updated_at` is updated on the user record, invalidating all previously issued JWTs.
- [ ] Audit events `PASSWORD_RESET_COMPLETED` and `CREDENTIALS_INVALIDATED` are recorded.
- [ ] On invalid or expired token: a generic error is returned — no token detail is leaked.
- [ ] The raw new password is never logged.

**Expected Result:** The password is updated and all previously issued JWTs are invalidated via `credentials_updated_at`. The user must log in with the new password.

---

## US-1.9: Authenticated Password Change

**As an** authenticated user  
**I want to** change my password while logged in  
**So that** I can update my credentials without needing a password reset flow

**Acceptance Criteria:**
- [ ] Requires a valid JWT access token.
- [ ] User must supply the current password for verification before any change is applied.
- [ ] New password is validated against strength policy and must differ from the current password.
- [ ] New password is hashed and persisted.
- [ ] `credentials_updated_at` is updated, invalidating all previously issued JWTs (including the current one after it expires naturally).
- [ ] Audit event `PASSWORD_CHANGED` and `CREDENTIALS_INVALIDATED` are recorded.
- [ ] Raw passwords are never logged at any point.

**Expected Result:** Password is updated. All previously issued JWTs are invalidated via `credentials_updated_at`. The user must log in again with the new password.

---

## US-1.10: Account Lockout After Failed Login Attempts

**As the** system  
**I want to** lock a user account after a configurable number of consecutive failed login attempts  
**So that** brute force credential attacks are mitigated

**Acceptance Criteria:**
- [ ] The failed attempt counter is incremented on every authentication failure for a recognized account.
- [ ] When the counter reaches the configured threshold (e.g., 5): the account is locked; `lockout_expiry` is set to `now + configurable window` (e.g., 15 minutes).
- [ ] Locked accounts return the same generic HTTP 401 as any other authentication failure.
- [ ] After the lockout window expires, the account automatically unlocks on the next successful login attempt.
- [ ] Audit event `ACCOUNT_LOCKED` is recorded when lockout is triggered.
- [ ] The failed attempt counter resets to zero on successful authentication.
- [ ] Admin can manually unlock an account before the lockout window expires (see US-8.3).

**Expected Result:** After the failure threshold is exceeded, the account enters a timed lockout. The attacker and the legitimate user receive identical generic error messages throughout.

---

## US-1.11: Email Change Flow

**As an** authenticated user  
**I want to** change the email address associated with my account  
**So that** my account reflects my current email address

**Acceptance Criteria:**
- [ ] Requires a valid JWT access token.
- [ ] User provides the new email address; it is validated for format and checked for uniqueness (with anti-enumeration: response is uniform regardless of collision).
- [ ] A verification token is generated and dispatched to the **new** email address.
- [ ] The current email remains active until the new one is verified.
- [ ] On verification: the email field is updated and audit event is recorded.
- [ ] If the new email is not verified within the token TTL, the pending change is discarded.
- [ ] All previously issued JWTs may be optionally invalidated on successful email change via `credentials_updated_at` (configurable).

**Expected Result:** The email update completes only after verification of the new address. The process is audited and does not expose other accounts' data.

---

## US-1.12: Token Theft Mitigation via Short-Lived JWTs

**As the** system  
**I want to** limit the damage window of a stolen JWT access token through short expiration times  
**So that** the impact of token theft is bounded by the token's remaining lifetime

**Acceptance Criteria:**
- [ ] JWT access tokens have a configurable TTL not to exceed 15 minutes (recommended: 5–15 minutes).
- [ ] A stolen JWT cannot be used beyond its `exp` claim; no server-side extension is possible.
- [ ] Forced invalidation of stolen tokens is achievable via `credentials_updated_at` when the theft is discovered (e.g., after a password change or admin-triggered forced re-authentication).
- [ ] Audit event `CREDENTIALS_INVALIDATED` is recorded when `credentials_updated_at` is updated.
- [ ] The short TTL is enforced as a non-negotiable security constraint in the JWT filter.

**Expected Result:** Stolen tokens expire quickly. When theft is known, `credentials_updated_at` provides an immediate invalidation mechanism without requiring session infrastructure.

---

# 2. Authorization & RBAC

## US-2.1: Role Creation

**As an** admin with `role:create` permission  
**I want to** create a new named role  
**So that** I can define functional access levels for user groups

**Acceptance Criteria:**
- [ ] Endpoint requires `role:create` authority enforced via `@PreAuthorize`.
- [ ] Accepts a role name (unique, non-blank) and optional description.
- [ ] Role names follow a consistent naming convention (e.g., uppercase alphanumeric).
- [ ] Duplicate role names are rejected with HTTP 409.
- [ ] New role is persisted with UUID, timestamps, and zero permissions initially.
- [ ] Audit event `ROLE_CREATED` is recorded with actor UUID.
- [ ] HTTP 201 returns the created role (UUID, name, description).

**Expected Result:** A new empty role is created and audited. Role names are unique system-wide.

---

## US-2.2: Permission Creation

**As an** admin with `permission:grant` permission  
**I want to** define a new fine-grained permission following the `resource:action` convention  
**So that** I can extend the permission taxonomy for new system resources

**Acceptance Criteria:**
- [ ] Endpoint requires `permission:grant` authority.
- [ ] Accepts name (e.g., `report:export`) and optional description.
- [ ] Permission names are unique; duplicates return HTTP 409.
- [ ] Permission names are immutable after creation — the name is the authority string used in JWT claims and `@PreAuthorize`.
- [ ] Persisted with UUID and timestamps; audit event recorded.

**Expected Result:** A new permission is available system-wide for assignment to roles or users.

---

## US-2.3: Assign Permission to Role

**As an** admin with `permission:grant` permission  
**I want to** assign an existing permission to an existing role  
**So that** all users holding that role inherit the permission

**Acceptance Criteria:**
- [ ] Endpoint requires `permission:grant` authority.
- [ ] Accepts role UUID and permission UUID.
- [ ] Both role and permission are validated to exist; HTTP 404 if not found.
- [ ] Idempotent: assigning an already-assigned permission produces no error and no duplicate record.
- [ ] Persists the `role_permissions` association.
- [ ] Audit event `PERMISSION_GRANTED` recorded with actor, role, and permission identifiers.

**Expected Result:** The permission is associated with the role. Users with this role gain the permission on their next token issuance.

---

## US-2.4: Revoke Permission from Role

**As an** admin with `permission:revoke` permission  
**I want to** remove a permission from a role  
**So that** users assigned that role lose the corresponding access on next token issuance

**Acceptance Criteria:**
- [ ] Endpoint requires `permission:revoke` authority.
- [ ] Validates both role and permission exist.
- [ ] Removes the `role_permissions` association.
- [ ] Audit event `PERMISSION_REVOKED` is recorded.
- [ ] Active JWT tokens retain the permission until they expire naturally (stateless limitation — mitigated by short TTL). Admins may update `credentials_updated_at` to force immediate re-authentication.

**Expected Result:** The permission is disassociated from the role. The change is effective for all newly issued access tokens.

---

## US-2.5: Assign Role to User

**As an** admin with `role:update` permission  
**I want to** assign a role to a user  
**So that** the user inherits all permissions defined by that role

**Acceptance Criteria:**
- [ ] Endpoint requires `role:update` authority.
- [ ] Accepts user UUID and role UUID; validates both exist.
- [ ] Idempotent if the user already holds the role.
- [ ] Persists `user_roles` association.
- [ ] Audit event `ROLE_ASSIGNED` recorded.
- [ ] New permissions take effect on the user's next token issuance.

**Expected Result:** The user is associated with the role and gains its permissions on next login.

---

## US-2.6: Revoke Role from User

**As an** admin with `role:update` permission  
**I want to** remove a role from a user  
**So that** the user loses the access granted by that role

**Acceptance Criteria:**
- [ ] Endpoint requires `role:update` authority.
- [ ] Removes the `user_roles` association.
- [ ] Audit event `ROLE_REMOVED` is recorded.
- [ ] Active JWTs retain the role's permissions until expiry; admin may update `credentials_updated_at` to force immediate re-authentication.

**Expected Result:** The user's role is removed. The change is effective for newly issued access tokens.

---

## US-2.7: Assign Direct Permission to User

**As an** admin with `permission:grant` permission  
**I want to** assign a permission directly to a specific user outside of any role  
**So that** I can grant fine-grained access overrides for individual users

**Acceptance Criteria:**
- [ ] Endpoint requires `permission:grant` authority.
- [ ] Accepts user UUID and permission UUID.
- [ ] Persists `user_permissions` association.
- [ ] Direct permissions are merged with role permissions in the JWT `authorities` claim at token issuance.
- [ ] Audit event `PERMISSION_GRANTED` recorded with context indicating direct assignment.

**Expected Result:** User receives a specific permission outside any role. Useful for exceptional, narrow access grants.

---

## US-2.8: Revoke Direct Permission from User

**As an** admin with `permission:revoke` permission  
**I want to** remove a directly assigned permission from a user  
**So that** the exceptional access grant is withdrawn

**Acceptance Criteria:**
- [ ] Endpoint requires `permission:revoke` authority.
- [ ] Removes the `user_permissions` association.
- [ ] Audit event `PERMISSION_REVOKED` recorded.
- [ ] Change takes effect on next token issuance.

**Expected Result:** The user no longer holds the directly assigned permission after their next login.

---

## US-2.9: Method-Level Authorization Enforcement

**As the** system  
**I want to** enforce fine-grained permission checks at the service method level via `@PreAuthorize`  
**So that** authorization is guaranteed server-side regardless of how an endpoint is reached

**Acceptance Criteria:**
- [ ] `@PreAuthorize` annotations are applied to all service methods performing sensitive operations.
- [ ] Authorization checks use authority strings (`hasAuthority('user:delete')`) derived from the JWT `authorities` claim.
- [ ] A request missing the required authority returns HTTP 403 with a standardized error body — the required permission is never named in the response.
- [ ] The 403 error body includes a correlation ID for traceability.
- [ ] Role names alone are insufficient — the specific permission string must be present in the token.
- [ ] Method security is enabled globally and cannot be bypassed by omitting controller-level checks.

**Expected Result:** Every sensitive operation is protected at the service layer. Missing permissions produce 403 without internal detail exposure.

---

## US-2.10: Access Denied Handling

**As an** authenticated user without the required permission  
**I want to** receive a clean, consistent 403 response  
**So that** I know my request was denied without the system exposing authorization internals

**Acceptance Criteria:**
- [ ] All 403 responses follow the standardized error envelope: error code, generic message, correlation ID.
- [ ] The response never reveals which permission is required, the user's current permission set, or role details.
- [ ] The 403 response body is identical across all endpoints.
- [ ] Stack traces, class names, and internal exception details are never present in the 403 body.

**Expected Result:** Unauthorized users receive a clean, non-revealing 403 on every endpoint.

---

## US-2.11: Least Privilege JWT Authority Claims

**As the** system  
**I want to** include only the minimal required authorities in the JWT access token  
**So that** token payloads are lean and do not over-expose the user's permission landscape

**Acceptance Criteria:**
- [ ] The JWT `authorities` claim contains the flattened effective permission set: union of all role permissions and any direct user permissions.
- [ ] The JWT does not contain verbose role names in a way that bypasses permission-level checks.
- [ ] The JWT does not contain email, phone, or any PII beyond the UUID subject claim.
- [ ] Token size is minimized using compact `resource:action` permission strings.
- [ ] Permissions in the token reflect state at issuance time; changes require a new login to take effect.

**Expected Result:** JWT payloads carry only compact permission strings and carry no sensitive personal data.

---

# 3. User Management

## US-3.1: Admin Creates a User

**As an** admin with `user:create` permission  
**I want to** create a new user account directly  
**So that** I can onboard users without requiring self-registration

**Acceptance Criteria:**
- [ ] Endpoint requires `user:create` authority.
- [ ] Accepts email and optional initial role assignments; email is validated and checked for uniqueness.
- [ ] A temporary activation email is sent, or a set-password link is dispatched.
- [ ] User is created in `PENDING_VERIFICATION` or `ACTIVE` status depending on admin flow configuration.
- [ ] Audit event `USER_CREATED` recorded with actor UUID.

**Expected Result:** A new user account is created by admin. The new user receives instructions to set or verify credentials.

---

## US-3.2: User Views Their Own Profile

**As an** authenticated user  
**I want to** view my own profile information  
**So that** I can see what data the system holds about me

**Acceptance Criteria:**
- [ ] Requires a valid JWT.
- [ ] Returns: UUID, masked email, account status, authentication origin, last login timestamp, role names, direct permission names.
- [ ] Never returns: password hash, token values, reset tokens, full IP history.
- [ ] Response uses a dedicated read DTO — entity is never exposed directly.

**Expected Result:** The user sees their own non-sensitive profile data. No credential or token data is included.

---

## US-3.3: Admin Views a User's Profile

**As an** admin with `user:read` permission  
**I want to** look up a user's profile by UUID  
**So that** I can support operational and administrative tasks

**Acceptance Criteria:**
- [ ] Endpoint requires `user:read` authority.
- [ ] Returns: UUID, masked email, status, origin, roles, direct permissions, last login, failed attempt count, lockout status.
- [ ] Never returns: password hash, token values, or full IP.
- [ ] HTTP 404 if user UUID does not exist.

**Expected Result:** Admin retrieves user details for operational purposes without credential or token data exposure.

---

## US-3.4: User Updates Their Own Profile

**As an** authenticated user  
**I want to** update permitted profile fields  
**So that** my account information remains current

**Acceptance Criteria:**
- [ ] Requires a valid JWT.
- [ ] Only fields within scope for this module (e.g., display name) are updatable via this endpoint.
- [ ] Email changes follow the dedicated email change flow (US-1.11).
- [ ] Password changes follow the dedicated password change flow (US-1.9).
- [ ] Updated fields are validated before persistence.
- [ ] Audit event recorded for meaningful field changes.

**Expected Result:** The user can update non-sensitive profile fields. Credential changes always go through dedicated secure flows.

---

## US-3.5: Admin Disables a User Account

**As an** admin with `user:update` permission  
**I want to** disable a user account  
**So that** the user can no longer authenticate while their data is preserved

**Acceptance Criteria:**
- [ ] Endpoint requires `user:update` authority.
- [ ] Transitions user status from `ACTIVE` to `INACTIVE`.
- [ ] `credentials_updated_at` is updated, invalidating all previously issued JWTs for the user.
- [ ] The disabled user's next request returns a generic HTTP 401 (account inactive check during JWT validation).
- [ ] Audit events `USER_DISABLED` and `CREDENTIALS_INVALIDATED` recorded with actor UUID and optional reason.
- [ ] The user record is preserved — not deleted.

**Expected Result:** The account is deactivated and all previously issued JWTs are invalidated. The user cannot authenticate until re-enabled.

---

## US-3.6: Admin Activates a User Account

**As an** admin with `user:update` permission  
**I want to** re-activate a disabled user account  
**So that** the user can authenticate again

**Acceptance Criteria:**
- [ ] Endpoint requires `user:update` authority.
- [ ] Transitions user status from `INACTIVE` to `ACTIVE`.
- [ ] Audit event `USER_ACTIVATED` recorded.
- [ ] User can log in immediately after activation.

**Expected Result:** The account is restored to active status and authentication is immediately possible.

---

## US-3.7: Admin Soft-Deletes a User Account

**As an** admin with `user:delete` permission  
**I want to** soft-delete a user account  
**So that** the user cannot access the system while data is retained for compliance and audit integrity

**Acceptance Criteria:**
- [ ] Endpoint requires `user:delete` authority.
- [ ] Sets `deleted_at` timestamp on the user record; no physical row removal.
- [ ] `credentials_updated_at` is updated, invalidating all previously issued JWTs.
- [ ] Soft-deleted users cannot authenticate — `deleted_at` is checked early in every login flow.
- [ ] Audit event `USER_DELETED` recorded; user UUID is preserved in all audit records for referential integrity.
- [ ] A future LGPD erasure process can anonymize PII fields on this record without deleting the audit trail.

**Expected Result:** The user is effectively removed from the system. All previously issued JWTs are invalidated. Audit trail integrity is maintained.

---

## US-3.8: Admin Lists Users

**As an** admin with `user:read` permission  
**I want to** retrieve a paginated list of users  
**So that** I can manage user accounts at scale

**Acceptance Criteria:**
- [ ] Endpoint requires `user:read` authority.
- [ ] Supports pagination (page, size) and filtering by status and role.
- [ ] Returns summary DTOs: UUID, masked email, status, origin, last login — no sensitive fields.
- [ ] Soft-deleted users are excluded by default; includable via explicit filter.
- [ ] Response includes total count for pagination.

**Expected Result:** Admins browse and filter users in a paginated, privacy-safe response.

---

# 4. Security & JWT Management

## US-4.1: JWT Access Token Generation

**As the** system  
**I want to** issue short-lived, signed JWT access tokens on successful authentication  
**So that** authenticated requests can be validated stateless without a per-request database lookup

**Acceptance Criteria:**
- [ ] JWT is signed with RS256 (asymmetric) or HS512 (symmetric) as configured via environment variable.
- [ ] Claims: `sub` (user UUID), `authorities` (permission array), `iat`, `exp`, `token_type`.
- [ ] `exp` is set to `iat + configured TTL` (e.g., 5–15 minutes).
- [ ] JWT contains no PII beyond the UUID subject: no email, no name, no sensitive metadata.
- [ ] Signing key is injected via environment variable — never hardcoded.
- [ ] Signature and expiry are validated on every protected request; no per-request DB lookup for JWT validation.
- [ ] `iat` is validated against `users.credentials_updated_at` — tokens issued before this timestamp are rejected.

**Expected Result:** A compact, minimal-claim, signed JWT is issued. Stateless validation adds sub-millisecond latency per request.

---

## US-4.2: JWT Expiry and Re-Authentication

**As the** system  
**I want to** enforce strict JWT expiration and require full re-authentication when a token expires  
**So that** the authentication window is bounded and credentials are regularly re-verified

**Acceptance Criteria:**
- [ ] JWT `exp` claim is strictly enforced; any request with an expired token returns HTTP 401.
- [ ] No token extension, sliding window, or server-side grace period exists beyond the configured TTL.
- [ ] The 401 response on expiry is indistinguishable in structure from any other authentication failure (standardized error body).
- [ ] The client must call the login endpoint to obtain a new JWT — there is no refresh mechanism.
- [ ] JWT TTL is configurable via environment variable; default must not exceed 15 minutes.
- [ ] Natural token expiry does not produce an `AUTH_FAILURE` audit event — it is a normal stateless outcome.

**Expected Result:** Expired tokens are rejected immediately. Users re-authenticate through the standard login flow. No silent token extension occurs.

---

## US-4.3: Lightweight JWT Invalidation via Credential Version

**As the** system  
**I want to** invalidate all previously issued JWTs for a user without maintaining a token revocation list  
**So that** forced re-authentication is possible after security events while remaining architecturally stateless

**Acceptance Criteria:**
- [ ] The `users` table contains a `credentials_updated_at` timestamp field.
- [ ] On every authenticated request, the JWT `iat` claim is compared against `credentials_updated_at`.
- [ ] If `iat` is before `credentials_updated_at`, the request is rejected with HTTP 401.
- [ ] `credentials_updated_at` is updated on: password change, password reset completion, account disable, admin-triggered forced re-authentication.
- [ ] Audit event `CREDENTIALS_INVALIDATED` is recorded each time `credentials_updated_at` is updated.
- [ ] The DB read for `credentials_updated_at` is lightweight and uses the indexed user UUID primary key.

**Expected Result:** Previously issued JWTs become invalid immediately after a credential-invalidating event. No session table or revocation list is required.

---

## US-4.4: Rate Limiting on Authentication Endpoints

**As the** system  
**I want to** apply rate limiting to authentication endpoints  
**So that** automated brute force and credential-stuffing attacks are throttled before reaching application logic

**Acceptance Criteria:**
- [ ] Rate limiting is enforced on: `/auth/login`, `/auth/register`, `/auth/password-reset`, and the resend verification endpoint.
- [ ] Limits are configurable per IP and optionally per email/account.
- [ ] Exceeding the limit returns HTTP 429 with a `Retry-After` header.
- [ ] The 429 response does not reveal whether the rate limit is per-IP or per-account.
- [ ] Rate limiting is implemented at the filter or gateway level and is independent of per-account lockout logic, providing layered defense.

**Expected Result:** High-volume automated attacks are blocked at the rate limiter. Account lockout logic operates as a second independent layer.

---

## US-4.5: HTTP Security Headers Enforcement

**As the** system  
**I want to** enforce a strict set of HTTP security headers on all responses  
**So that** clients are protected from XSS, clickjacking, MIME sniffing, and related browser-based attacks

**Acceptance Criteria:**
- [ ] `X-Content-Type-Options: nosniff` on all responses.
- [ ] `X-Frame-Options: DENY` on all responses.
- [ ] `Content-Security-Policy` configured with a strict policy.
- [ ] `Strict-Transport-Security: max-age=31536000; includeSubDomains` in production environments.
- [ ] `Referrer-Policy: strict-origin-when-cross-origin` on all responses.
- [ ] `Cache-Control: no-store` on all authentication endpoint responses.
- [ ] All headers are configured centrally in Spring Security — not scattered in controllers.

**Expected Result:** All responses carry security headers as a defense-in-depth layer, protecting clients from common browser-level attack vectors.

---

## US-4.6: Suspicious Login Contextual Logging

**As the** system  
**I want to** capture contextual metadata on every login attempt  
**So that** security teams can detect suspicious access patterns and investigate incidents

**Acceptance Criteria:**
- [ ] Each login attempt (success or failure) records: masked IP (last octet zeroed), truncated user-agent, UTC timestamp.
- [ ] Audit records are queryable by user UUID, event type, and time range.
- [ ] No full IP address is stored — partial masking complies with LGPD.
- [ ] Correlation IDs are present in all log entries for distributed tracing.

**Expected Result:** Contextual metadata is available for forensic analysis without storing full PII-level network identifiers.

---

# 5. Audit & Monitoring

## US-5.1: Authentication Event Audit Log

**As an** admin with `audit:view` permission  
**I want to** access a structured, queryable log of all authentication lifecycle events  
**So that** I can investigate security incidents and monitor authentication health

**Acceptance Criteria:**
- [ ] Captures all events in the taxonomy: `USER_REGISTERED`, `AUTH_SUCCESS`, `AUTH_FAILURE`, `ACCOUNT_LOCKED`, `AUTH_LOGOUT`, `CREDENTIALS_INVALIDATED`, `PASSWORD_RESET_REQUESTED`, `PASSWORD_RESET_COMPLETED`, `PASSWORD_CHANGED`, `EMAIL_VERIFIED`.
- [ ] Each record: UUID, event type, actor UUID (nullable), target UUID (nullable), masked IP, truncated user-agent, UTC timestamp, outcome (`SUCCESS`/`FAILURE`), metadata JSON (no sensitive fields).
- [ ] Endpoint requires `audit:view` authority.
- [ ] Supports pagination and filtering by event type, user UUID, outcome, and date range.
- [ ] Records are append-only — no update or delete endpoint exists.

**Expected Result:** A queryable, immutable audit log is available to authorized admins covering all authentication lifecycle events.

---

## US-5.2: Permission and Role Change Audit

**As an** admin with `audit:view` permission  
**I want to** see a full history of all access control changes  
**So that** I can track who granted or revoked access and when, supporting access review and compliance

**Acceptance Criteria:**
- [ ] Events `PERMISSION_GRANTED`, `PERMISSION_REVOKED`, `ROLE_ASSIGNED`, `ROLE_REMOVED` are captured.
- [ ] Each record includes: actor UUID (admin who made the change), target user UUID, role/permission name, UTC timestamp.
- [ ] Queryable via the standard admin audit endpoint with appropriate filters.

**Expected Result:** A complete, auditable timeline of access control changes supports compliance reviews.

---

## US-5.3: Failed Authentication Tracking

**As an** admin with `audit:view` permission  
**I want to** query failed authentication events  
**So that** I can identify accounts under attack and investigate credential-stuffing patterns

**Acceptance Criteria:**
- [ ] All `AUTH_FAILURE` and `ACCOUNT_LOCKED` events are captured in the audit log.
- [ ] Records include masked IP, truncated user-agent, timestamp, and outcome=FAILURE.
- [ ] Target user UUID is included where a matching account exists (for admin queries), but failure reason is not exposed externally.
- [ ] Queryable by time range, masked IP, and event type.

**Expected Result:** Admins can identify attack patterns, affected accounts, and failure sequence timelines.

---

## US-5.4: Credentials Invalidation Audit Event

**As the** system  
**I want to** record every `credentials_updated_at` update as a structured audit event  
**So that** security teams can trace forced re-authentication events to their originating cause

**Acceptance Criteria:**
- [ ] `CREDENTIALS_INVALIDATED` event is recorded with: masked IP, user-agent, UTC timestamp, affected user UUID, and trigger reason (PASSWORD_CHANGED, PASSWORD_RESET, ACCOUNT_DISABLED, ADMIN_FORCED_REAUTH).
- [ ] The event is persisted before the `credentials_updated_at` update is applied, ensuring the record exists even if the update fails.
- [ ] The audit sink architecture supports future integration with alerting systems (webhook, SIEM) without redesign.
- [ ] The trigger reason is stored in the metadata JSON and is never exposed in client-facing responses.

**Expected Result:** All forced re-authentication events are captured with a traceable reason, enabling security investigation and compliance reporting.

---

## US-5.5: Per-User Activity Timeline

**As an** admin with `audit:view` permission  
**I want to** view a chronological event timeline for a specific user  
**So that** I can understand a user's full authentication and access history for support or forensic purposes

**Acceptance Criteria:**
- [ ] Endpoint accepts a user UUID and returns their audit events in reverse chronological order.
- [ ] Includes: login attempts, logouts, password changes, credentials invalidation events, role and permission changes.
- [ ] Response is paginated.
- [ ] Requires `audit:view` authority.
- [ ] All sensitive fields are masked or truncated in the response.

**Expected Result:** A per-user event timeline is accessible to authorized admins for security investigation and user support.

---

## US-5.6: Audit Log Data Protection

**As the** system  
**I want to** ensure audit records never contain sensitive credential or personal data  
**So that** the audit trail itself does not become a security or LGPD liability

**Acceptance Criteria:**
- [ ] Passwords are never included in any audit record or log line at any log level.
- [ ] JWT values and reset tokens are never logged verbatim.
- [ ] Email addresses in audit records are masked before persistence.
- [ ] IP addresses are masked (e.g., last octet zeroed) before persistence.
- [ ] User-agent strings are truncated to a configured maximum length.
- [ ] Metadata JSON in audit records is scrubbed of sensitive values by a centralized utility before storage.

**Expected Result:** The audit log is comprehensive for security analysis and fully safe from credential exposure and LGPD data liability.

---

## US-5.7: Structured JSON Logging with Correlation IDs

**As a** system operator  
**I want to** receive structured JSON logs with correlation IDs on all requests  
**So that** logs integrate with observability stacks (ELK, Loki, CloudWatch) and support distributed tracing

**Acceptance Criteria:**
- [ ] All log output is in JSON format with consistent, predictable field names.
- [ ] A correlation ID (UUID generated per request) is injected into the MDC and included in every log line for that request.
- [ ] The correlation ID is returned in all API error response bodies, enabling log correlation from client reports.
- [ ] Log levels are configurable per package via environment variables.
- [ ] Sensitive fields (passwords, tokens, full emails) are never emitted regardless of log level.

**Expected Result:** Structured logs with correlation IDs enable efficient incident investigation and seamless integration with centralized logging infrastructure.

---

# 6. Privacy & LGPD Compliance

## US-6.1: Minimal JWT Payload (Data Minimization)

**As the** system  
**I want to** ensure JWT access tokens carry only the operationally required claims  
**So that** token interception does not expose personal data, aligning with LGPD data minimization

**Acceptance Criteria:**
- [ ] JWT `sub` claim contains the user UUID — not email, name, or any PII.
- [ ] JWT `authorities` claim contains only compact permission strings.
- [ ] JWT never contains: email, phone, CPF/document numbers, full name, role hierarchy, or any field classified as sensitive under LGPD Article 11.
- [ ] A security test enforces these constraints as part of the CI suite — token inspection is automated.

**Expected Result:** JWT payloads contain no personal data beyond what is operationally necessary. Token interception yields no PII.

---

## US-6.2: Sensitive Data Masking in Logs and Audit Records

**As the** system  
**I want to** mask or omit all sensitive personal data in logs and audit records  
**So that** the logging infrastructure does not create a LGPD compliance risk

**Acceptance Criteria:**
- [ ] Email addresses are masked in all log output and audit records (e.g., `a***@example.com`).
- [ ] IP addresses are truncated before persistence (e.g., `192.168.1.0` — last octet zeroed).
- [ ] No password, token, or secret value appears in any log at any level.
- [ ] Masking is applied by a centralized utility, not ad hoc per class.
- [ ] Masking rules apply uniformly to application logs and audit event metadata.

**Expected Result:** Log infrastructure stores contextually useful security data without retaining PII in forms requiring LGPD data subject access controls.

---

## US-6.3: Anti-Enumeration Uniform Responses

**As the** system  
**I want to** return uniform, non-revealing responses on all authentication, registration, and password reset flows  
**So that** attackers cannot use the API to enumerate valid email addresses

**Acceptance Criteria:**
- [ ] Registration with an already-registered email returns the same response as successful registration; internally an "account already exists" email is dispatched.
- [ ] Login with a non-existent email returns the same generic HTTP 401 as a wrong password.
- [ ] Login on a locked account returns the same generic HTTP 401.
- [ ] Password reset initiation always returns HTTP 200 regardless of email existence.
- [ ] Email verification with an invalid or expired token returns a generic error without indicating account or email existence.
- [ ] All auth-flow error messages are reviewed to confirm zero leakage of existence information.

**Expected Result:** The API is enumeration-resistant. No authentication endpoint reveals email existence to unauthenticated callers.

---

## US-6.4: Consent Capture at Registration

**As an** anonymous user registering an account  
**I want to** record my consent to data processing at registration time  
**So that** the system documents my consent in alignment with LGPD requirements

**Acceptance Criteria:**
- [ ] Registration DTO includes a consent acceptance flag (boolean, required).
- [ ] Consent is validated as required; registration is rejected without it.
- [ ] Consent acceptance timestamp and version are stored on the user record.
- [ ] Audit event `CONSENT_ACCEPTED` is recorded.
- [ ] Consent is tracked independently from email verification — both are required and separately audited.
- [ ] The architecture supports future consent revocation and versioning without schema redesign (consent version field).

**Expected Result:** User consent is captured, timestamped, and versioned at registration, establishing a foundation for full LGPD consent lifecycle management.

---

## US-6.5: Soft-Delete for LGPD Right-to-Erasure Readiness

**As an** admin with `user:delete` permission  
**I want to** soft-delete a user account without physical row removal  
**So that** the system supports LGPD right-to-erasure requests without destroying audit trail integrity

**Acceptance Criteria:**
- [ ] Soft delete sets `deleted_at` on the user record — no physical row removal.
- [ ] User UUID is preserved in `audit_events` as a foreign key; audit trail integrity is maintained.
- [ ] A future anonymization pipeline can zero out PII fields (email, name) while preserving UUID and audit associations.
- [ ] Soft-deleted users cannot authenticate — `deleted_at` check precedes all credential operations.

**Expected Result:** Data is retained for audit integrity after logical deletion. A clear, documented path exists for PII anonymization on erasure requests.

---

## US-6.6: Data Portability Readiness

**As an** authenticated user  
**I want to** be able to request an export of my personal data  
**So that** the system supports LGPD data portability rights

**Acceptance Criteria:**
- [ ] The user data entity and DTOs are structured so that all personal fields can be aggregated into an exportable format (JSON/CSV) without schema changes.
- [ ] PII is not scattered across tables in ways that make aggregation impractical.
- [ ] A future export endpoint can be added as a feature without architectural rework.
- [ ] Exportable data includes: email, account origin, role names, consent timestamp, account creation date.
- [ ] Exported data excludes: password hash, token hashes, raw internal tokens.

**Expected Result:** The data model is LGPD-portability-ready. A future export endpoint is an additive feature, not a redesign.

---

## US-6.7: Hashed Storage of Security Tokens

**As the** system  
**I want to** store password reset tokens exclusively as cryptographic hashes  
**So that** database compromise does not yield usable token values

**Acceptance Criteria:**
- [ ] Reset tokens are generated using a cryptographically secure random source (UUID v4 or 256-bit random).
- [ ] Only the hash of each token is persisted — raw values are transmitted once (via email) and never stored.
- [ ] Raw token values are never logged.
- [ ] Reset tokens are single-use: consumed on first valid use.
- [ ] Reset tokens have explicit `expires_at` timestamps and are subject to scheduled cleanup.

**Expected Result:** Token storage is breach-resistant. Database exposure does not yield usable tokens.

---

## US-6.8: Configurable Token and Log Retention

**As a** system operator  
**I want to** configure retention windows for sensitive tokens and audit records  
**So that** data is not retained longer than necessary, aligning with LGPD data minimization over time

**Acceptance Criteria:**
- [ ] Consumed password reset tokens are purgeable after a configurable retention window post-consumption.
- [ ] Audit events have a configurable retention TTL (e.g., 90 days, 1 year).
- [ ] Purge operations are themselves logged.
- [ ] Purge does not cascade-delete audit records that reference purged user UUIDs — foreign keys use nullable or retained references.

**Expected Result:** Token and log tables do not grow unboundedly. Configurable retention supports LGPD data minimization.

---

# 7. OAuth2 & External Authentication

## US-7.1: Google OAuth2 Login and Registration

**As an** anonymous user  
**I want to** log in or register using my Google account  
**So that** I can access the system without managing a local password

**Acceptance Criteria:**
- [ ] The system exposes an OAuth2 Authorization Code flow entry point via Spring Security OAuth2 Client.
- [ ] The user is redirected to Google's authorization endpoint with a secure, random state parameter.
- [ ] After Google returns the authorization code, the system exchanges it for tokens and retrieves the profile: email, display name, Google provider ID.
- [ ] If the email does not exist: a new user is auto-created with `ACTIVE` status, email marked verified, origin `GOOGLE`, and provider ID stored.
- [ ] A JWT access token is issued identically to the local login flow.
- [ ] Audit event `USER_REGISTERED_GOOGLE` (new account) or `AUTH_SUCCESS` (returning user) is recorded.
- [ ] Brute force counters and lockout checks apply to the resolved user account after OAuth2 resolution.
- [ ] Minimum required OAuth2 scopes are requested: `openid email profile`.

**Expected Result:** Google OAuth2 provides seamless registration and login. The resulting JWT is structurally identical to one issued via local authentication.

---

## US-7.2: Google Account Linking to Existing Local Account

**As an** existing local-account user  
**I want to** link my Google identity to my existing account  
**So that** I can use Google login on my pre-existing account

**Acceptance Criteria:**
- [ ] If a Google OAuth2 flow resolves an email that already exists as a `LOCAL` account: the Google provider ID is associated and origin is updated to `MIXED`.
- [ ] The linking is transparent — the user is logged in after the OAuth2 flow completes.
- [ ] Audit event `ACCOUNT_LINKED_GOOGLE` is recorded.
- [ ] After linking, the user can log in via either Google or local email/password.
- [ ] The existing local password is not invalidated by linking.

**Expected Result:** Users can link their Google identity to a pre-existing local account, enabling both login methods.

---

## US-7.3: Google OAuth2 Failure Handling

**As an** anonymous user  
**I want to** receive a safe, non-revealing error if the Google OAuth2 flow fails  
**So that** I understand login failed without internal state being exposed

**Acceptance Criteria:**
- [ ] If the OAuth2 flow fails (user cancels, Google error, invalid state/CSRF, exchange failure): the system redirects to a configured error path or returns HTTP 400/401 with a generic message.
- [ ] Google error details are logged internally but never forwarded to the client.
- [ ] No partial user record is created on flow failure.
- [ ] Audit event `AUTH_FAILURE` is recorded with provider context.

**Expected Result:** OAuth2 failures are handled gracefully. No internals are exposed and no orphaned records are created.

---

## US-7.4: Revoke Google Provider Link

**As an** authenticated user with `MIXED` or `GOOGLE` origin  
**I want to** unlink my Google account  
**So that** I restrict login to local credentials only

**Acceptance Criteria:**
- [ ] Endpoint requires authentication.
- [ ] For `MIXED` origin: removes the Google provider ID, sets origin to `LOCAL`.
- [ ] For `GOOGLE`-only users: unlinking is rejected unless a local password has been set, to prevent account lockout.
- [ ] Audit event `PROVIDER_UNLINKED` recorded.
- [ ] Active JWTs may be optionally invalidated on unlink via `credentials_updated_at` (configurable policy).

**Expected Result:** Users can remove their Google identity while retaining local credential access. Accounts cannot be orphaned without a login method.

---

## US-7.5: OAuth2 State Parameter CSRF Protection

**As the** system  
**I want to** validate the OAuth2 state parameter on the callback  
**So that** CSRF-injected authorization codes are rejected

**Acceptance Criteria:**
- [ ] A random state parameter is generated at flow initiation and stored (session or server-side cookie) for validation.
- [ ] The callback validates the returned state matches the stored state before processing the code.
- [ ] State mismatch results in HTTP 400 and an audit log entry.
- [ ] Spring Security OAuth2 Client's built-in state management handles this by default; configuration must not disable it.

**Expected Result:** The OAuth2 callback is CSRF-protected via validated state parameter.

---

## US-7.6: OAuth2 Profile Data Minimization

**As the** system  
**I want to** collect only the minimum required fields from the Google profile  
**So that** LGPD data minimization principles apply to social login onboarding

**Acceptance Criteria:**
- [ ] Only email, display name (optional), and Google provider ID are extracted and stored.
- [ ] No phone number, date of birth, profile photo URL, or other PII is persisted.
- [ ] Google access tokens and refresh tokens obtained during OAuth2 are not stored by this system.
- [ ] Requested scopes are minimum required: `openid email profile`.

**Expected Result:** Social login collects only the minimum data needed for identity resolution. No extraneous Google profile data enters the system.

---

# 8. Administrative Security Operations

## US-8.1: Admin Force Re-Authentication for User

**As an** admin with `auth:manage` permission  
**I want to** force a specific user to re-authenticate  
**So that** I can immediately invalidate all their active JWTs in response to a security incident or policy violation

**Acceptance Criteria:**
- [ ] Endpoint requires `auth:manage` authority.
- [ ] Accepts a target user UUID.
- [ ] `credentials_updated_at` is updated on the target user record to the current timestamp.
- [ ] All JWTs issued before this timestamp are immediately rejected on their next request.
- [ ] Audit event `CREDENTIALS_INVALIDATED` recorded with actor admin UUID, target user UUID, and reason `ADMIN_FORCED_REAUTH`.
- [ ] The user must perform a full re-authentication to continue accessing the system.

**Expected Result:** The target user's existing JWTs are invalidated on their next use. The action is fully audited with actor traceability.

---

## US-8.2: Admin Manually Locks an Account

**As an** admin with `auth:manage` permission  
**I want to** manually lock a user account  
**So that** I can immediately prevent a suspected-compromised or policy-violating account from authenticating

**Acceptance Criteria:**
- [ ] Endpoint requires `auth:manage` authority.
- [ ] Transitions user to `LOCKED` status with a reason stored on the record.
- [ ] `credentials_updated_at` is updated, invalidating all previously issued JWTs.
- [ ] Admin-applied lockout is permanent (no auto-expiry) until manually unlocked — distinguishable from auto-lockout by reason field or lock type.
- [ ] Audit events `ACCOUNT_LOCKED` and `CREDENTIALS_INVALIDATED` recorded with actor UUID and reason.

**Expected Result:** Admin can immediately and permanently lock any account for security or compliance purposes. All existing JWTs are invalidated.

---

## US-8.3: Admin Manually Unlocks an Account

**As an** admin with `auth:manage` permission  
**I want to** manually unlock a locked user account  
**So that** I can restore access for incorrectly locked users or users who have resolved the cause of lockout

**Acceptance Criteria:**
- [ ] Endpoint requires `auth:manage` authority.
- [ ] Transitions user from `LOCKED` to `ACTIVE`.
- [ ] Resets the failed attempt counter to zero and clears the `lockout_expiry` timestamp.
- [ ] Audit event `ACCOUNT_UNLOCKED` recorded with actor UUID.
- [ ] User can authenticate immediately after unlock.

**Expected Result:** Admin restores account access with a clean authentication slate.

---

## US-8.4: Admin Queries the Audit Log

**As an** admin with `audit:view` permission  
**I want to** query audit events with filters  
**So that** I can investigate security incidents and generate compliance reports

**Acceptance Criteria:**
- [ ] Endpoint requires `audit:view` authority.
- [ ] Filtering: event type, actor UUID, target UUID, date range, outcome.
- [ ] Paginated results in reverse chronological order.
- [ ] Sensitive fields are masked in the response.
- [ ] Audit log is read-only via API — no modification or deletion endpoint.

**Expected Result:** Admins efficiently query and analyze the audit trail for security and compliance purposes.

---

## US-8.5: Admin Manages Roles

**As an** admin with role management permissions  
**I want to** create, update, and delete roles  
**So that** the access control taxonomy stays aligned with organizational needs

**Acceptance Criteria:**
- [ ] Create (`role:create`): accepts name and description; returns created role; rejects duplicates with HTTP 409.
- [ ] Update (`role:update`): can update description; role name is immutable after creation (authority string integrity).
- [ ] Delete (`role:delete`): blocked with HTTP 409 if the role is currently assigned to any user.
- [ ] All operations are audited.
- [ ] Role list endpoint requires `role:create` or `role:update` authority.

**Expected Result:** Full role lifecycle management is available. Destructive operations are safeguarded against breaking existing user assignments.

---

## US-8.6: Admin Manages Permissions

**As an** admin with permission management permissions  
**I want to** view and manage the permission taxonomy  
**So that** permissions remain accurate and aligned as the system evolves

**Acceptance Criteria:**
- [ ] List permissions: accessible to `permission:grant` or `audit:view`.
- [ ] Create (`permission:grant`): accepts name and description; names are unique and immutable.
- [ ] Delete (`permission:revoke`): blocked with HTTP 409 if the permission is assigned to any role or user.
- [ ] All operations are audited.

**Expected Result:** The permission catalog is manageable by authorized admins. Safety guards prevent orphaned assignments.

---

## US-8.7: Security Operations Summary Endpoint

**As an** admin with `audit:view` permission  
**I want to** retrieve aggregated security metrics  
**So that** I can monitor the system's security posture without direct database access

**Acceptance Criteria:**
- [ ] Endpoint requires `audit:view` authority.
- [ ] Returns aggregated counts: locked accounts, failed login attempts in the last N hours, forced re-authentication events in the last N hours.
- [ ] No individual user PII is included — aggregate counts only.
- [ ] Suitable as a data source for an operations dashboard or alerting integration.

**Expected Result:** A lightweight security summary is available for operational monitoring and integration with dashboards or alerting.

---

# 9. Non-Functional & Architecture Requirements

## US-9.1: Stateless Horizontal Scalability

**As a** system architect  
**I want to** ensure the authentication module supports stateless horizontal scaling  
**So that** multiple instances can run simultaneously without shared in-memory session state

**Acceptance Criteria:**
- [ ] No `HttpSession` is used for authentication state — all state lives in the JWT or the database.
- [ ] The JWT signing key is externalized via environment variable; not generated per-instance at startup.
- [ ] `credentials_updated_at` lookups use the shared PostgreSQL database accessible by all instances.
- [ ] The application runs as N identical replicas behind a load balancer with no session affinity requirement.
- [ ] Health check (`/actuator/health`) and readiness endpoints are available for orchestrator integration.

**Expected Result:** The module is inherently horizontally scalable. Any instance can serve any request without coordination.

---

## US-9.2: Enforced Layered Architecture

**As a** developer  
**I want to** work within a codebase with a clearly enforced layered architecture  
**So that** responsibilities are separated, the code is maintainable, and the module is extensible

**Acceptance Criteria:**
- [ ] Four enforced layers: Controller, Service, Repository, Domain — with directional dependency rules.
- [ ] JPA entities never cross the service boundary into response DTOs; the API layer only uses DTOs.
- [ ] Services depend on repository interfaces, not concrete implementations.
- [ ] A global `@ControllerAdvice` handles all domain exception → HTTP response mapping uniformly.
- [ ] Security configuration is centralized in a dedicated configuration class.
- [ ] No business logic exists in controllers or repositories.

**Expected Result:** The codebase is maintainable, testable, and extensible. Cross-cutting concerns are enforced by architecture, not convention.

---

## US-9.3: Docker and Cloud-Native Deployment Readiness

**As a** DevOps engineer  
**I want to** deploy the authentication module as a containerized service  
**So that** it integrates into Docker and Kubernetes-based infrastructure

**Acceptance Criteria:**
- [ ] A `Dockerfile` is provided using a multi-stage build optimized for image size and security.
- [ ] All secrets and environment-specific configuration are injected via environment variables (12-factor app).
- [ ] No secrets appear in any committed configuration file.
- [ ] A `docker-compose.yml` is available for local development with PostgreSQL.
- [ ] The application exposes `/actuator/health` for liveness and readiness probes.
- [ ] Flyway migrations run on startup in development; production startup behavior is configurable.
- [ ] Application fails fast with a clear error if required environment variables are absent.

**Expected Result:** The module is deployable in any container environment. Configuration is fully externalized.

---

## US-9.4: Externalized Secret Management

**As a** security engineer  
**I want to** ensure no secrets are hardcoded or present in version control  
**So that** source code exposure does not compromise operational security

**Acceptance Criteria:**
- [ ] JWT signing keys, database credentials, and OAuth2 client credentials are loaded exclusively from environment variables.
- [ ] `application.yml` contains no secret values — only non-sensitive configuration structure and environment variable bindings.
- [ ] A `.env.example` documents all required variables without values.
- [ ] Application startup fails fast with a clear error if required variables are missing.
- [ ] No secret value appears in any log output.

**Expected Result:** The codebase is safe to publish. All sensitive configuration is runtime-injected.

---

## US-9.5: Database Schema Management via Flyway

**As a** developer or DBA  
**I want to** manage all database schema changes through Flyway versioned migrations  
**So that** schema evolution is tracked, reproducible, and safe for production deployments

**Acceptance Criteria:**
- [ ] Flyway is the sole mechanism for schema changes; `ddl-auto` is `validate` or `none` in production.
- [ ] All migrations are versioned (`V{n}__{description}.sql`) in `src/main/resources/db/migration`.
- [ ] The baseline migration defines all entities with correct UUID PKs, constraints, indexes, and foreign keys.
- [ ] Migration checksums are validated on startup; tampered migrations block application startup.
- [ ] Indexes are defined in migrations (not deferred to Hibernate): user email, reset token hash, audit event user UUID and timestamp.

**Expected Result:** The database schema is fully version-controlled. Production deployments are safe from schema drift.

---

## US-9.6: Input Validation at the API Boundary

**As the** system  
**I want to** validate all incoming request DTOs before any business logic executes  
**So that** malformed or invalid input is rejected early and never reaches the service layer

**Acceptance Criteria:**
- [ ] All request DTOs use Jakarta Validation annotations: `@NotBlank`, `@Email`, `@Size`, custom password strength constraint.
- [ ] `@Valid` is applied to all controller method parameters.
- [ ] Validation failures return HTTP 400 with a structured field-level error body.
- [ ] The 400 body never exposes stack traces or internal class names.
- [ ] Email format is validated before any database lookup is performed.

**Expected Result:** Invalid input is rejected at the controller boundary with a clean 400. The service layer operates only on validated data.

---

## US-9.7: Standardized API Response Envelope

**As an** API consumer  
**I want to** receive consistent, predictable response structures from all endpoints  
**So that** client-side handling is uniform and self-describing

**Acceptance Criteria:**
- [ ] All error responses follow: `{ "errorCode": "...", "message": "...", "correlationId": "..." }`.
- [ ] HTTP status codes follow REST conventions strictly: 200/201 (success), 400 (validation), 401 (authentication), 403 (authorization), 404 (not found), 409 (conflict), 429 (rate limit), 500 (server error).
- [ ] 500 responses never expose stack traces, exception class names, or SQL error messages.
- [ ] `Cache-Control: no-store` is set on all auth endpoint responses.

**Expected Result:** API consumers can rely on a consistent response structure. Internal details are never leaked via error responses.

---

## US-9.8: Stateless JWT Validation Performance

**As a** system architect  
**I want to** validate JWT access tokens without a full session lookup on every authenticated request  
**So that** the authentication layer does not become a database bottleneck under load

**Acceptance Criteria:**
- [ ] JWT validation (signature + expiry) is performed in memory using the configured signing key.
- [ ] The only DB access during normal authenticated requests is a lightweight single-row read of `credentials_updated_at` by user UUID primary key.
- [ ] The JWT filter runs early in the Spring Security filter chain, before protected endpoint handlers.
- [ ] Token expiry is enforced by the JWT `exp` claim — no active token registry for general requests.
- [ ] Authenticated request processing adds negligible latency (target: sub-millisecond JWT filter overhead plus indexed PK lookup).

**Expected Result:** Authenticated endpoint latency is minimal. No session table, no token list — only a single fast PK lookup per request.

---

## US-9.9: Security-Focused Test Suite

**As a** developer  
**I want to** have a comprehensive test suite covering authentication flows, authorization, and security edge cases  
**So that** security regressions are caught before production and security properties are continuously verified

**Acceptance Criteria:**
- [ ] Unit tests cover: password hashing, JWT generation and validation, RBAC permission evaluation, input validation, `credentials_updated_at` invalidation logic.
- [ ] Integration tests cover: full login flow, JWT expiry behavior, forced re-authentication via `credentials_updated_at`, account lockout, and password reset flow.
- [ ] Security-specific tests enforce: anti-enumeration responses (uniform error shapes), JWT claim contents (no PII present), access denied response shape.
- [ ] Integration tests use a real PostgreSQL instance (Testcontainers) — no mocked repositories for core auth flows.
- [ ] The test suite runs in CI/CD and gates merges and deployments.

**Expected Result:** The module's security properties are continuously tested. Security regressions are blocked before they reach production.

---

## US-9.10: OWASP Top 10 and ASVS Level 2 Alignment

**As a** security engineer  
**I want to** verify that the module addresses OWASP Top 10 risks and aligns with ASVS Level 2  
**So that** the module meets a recognized enterprise security baseline

**Acceptance Criteria:**
- [ ] **A01 Broken Access Control**: server-side RBAC with `@PreAuthorize` enforcement on every sensitive operation.
- [ ] **A02 Cryptographic Failures**: Argon2id for passwords; RS256/HS512 for JWT signing; hashed token storage for all security tokens.
- [ ] **A03 Injection**: Spring Data JPA with parameterized queries only; no dynamic query construction.
- [ ] **A04 Insecure Design**: stateless architecture, short-lived tokens, secure-by-default configuration.
- [ ] **A05 Security Misconfiguration**: explicit CORS policy with no wildcard; security headers enforced; no debug/devtools active in production.
- [ ] **A07 Identification and Authentication Failures**: brute force protection with lockout, credential-version invalidation, forced re-authentication capability.
- [ ] **A09 Security Logging and Monitoring**: structured audit logging with correlation IDs, full authentication lifecycle event taxonomy, high-severity event classification for credentials invalidation.
- [ ] An ASVS Level 2 checklist review covers authentication, session management, and access control chapters, with findings documented.

**Expected Result:** The module's controls map to OWASP Top 10 mitigations and ASVS Level 2. A verifiable enterprise security baseline is established.