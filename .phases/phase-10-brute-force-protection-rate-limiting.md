## Phase 10 — Brute Force Protection & Rate Limiting

**Objective:** Implement per-account lockout threshold logic and IP-based rate limiting as independent defense layers.

**Dependencies:** Phase 6.2 (AuthService has lockout hooks).

**Complexity:** Medium

### Phase 10.1 — Login Attempt Tracking & Lockout

**Implementation Tasks:**

- [ ] Create `BruteForceProtectionService.java`:
  - `void recordAttempt(UUID userId, String ipMasked, String userAgentTruncated, String authMethodSlug, boolean success, String failureContext)` — saves `LoginAttempt`
  - `boolean isAccountLocked(User user)` — checks `lockoutExpiresAt` against `now()`; auto-clears expired automatic lockouts
  - `void incrementFailedAttempts(User user)` — increments counter; if threshold reached: sets lockout fields, saves `AccountLockout` record, records `ACCOUNT_LOCKED` audit event
  - `void resetFailedAttempts(User user)` — resets counter to 0, clears `lockoutExpiresAt`
  - All thresholds driven by `AppProperties` (not hardcoded)

**Acceptance Criteria:**
- [ ] AUTOMATIC lockout: `lockoutExpiresAt = now() + lockoutDurationMinutes`, auto-clears after window
- [ ] MANUAL lockout: no `lockoutExpiresAt`, not cleared by time passage — only by admin `unlockAccount`
- [ ] Failed attempt counter resets on any successful login
- [ ] `failure_context` stored internally; **never** returned to API caller

**Automated Tests:**
- [ ] `BruteForceProtectionServiceTest`:
  - 5 failures → account locked
  - Successful login → counter reset
  - Expired auto-lockout → cleared on next attempt
  - MANUAL lock → not cleared by time
- [ ] `AccountLockoutIntegrationTest`:
  - 5 failed login requests → 6th request returns same 401 (lockout active)
  - Wait for lockout window (or manipulate DB) → login succeeds

---

### Phase 10.2 — Rate Limiting Filter

**Implementation Tasks:**

- [ ] Create `RateLimitingFilter.java extends OncePerRequestFilter`:
  - Applies to: `/api/v1/auth/login`, `/api/v1/auth/register`, `/api/v1/auth/password-reset/request`, `/api/v1/auth/email/verify/resend`
  - IP-based rate limiting using in-memory `ConcurrentHashMap<String, RateLimitBucket>` (or Resilience4j/Bucket4j if on classpath)
  - On limit exceeded: return 429 with `Retry-After` header
  - 429 response body: `{ "errorCode": "RATE_LIMITED", "message": "Too many requests.", "correlationId": "..." }`
  - 429 response must not distinguish IP-based from account-based limiting
  - Rate limit parameters configurable via `AppProperties`

**Acceptance Criteria:**
- [ ] Exceeding limit returns exactly HTTP 429 with `Retry-After` header
- [ ] Rate limiting is independent of per-account lockout logic
- [ ] Filter does not apply to non-auth endpoints

**Automated Tests:**
- [ ] `RateLimitingFilterTest`:
  - N+1 requests within window → 429
  - Different IPs have independent rate limit buckets
  - After window reset → requests succeed again

---

