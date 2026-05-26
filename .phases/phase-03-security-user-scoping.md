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

