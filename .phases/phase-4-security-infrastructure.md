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

