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

