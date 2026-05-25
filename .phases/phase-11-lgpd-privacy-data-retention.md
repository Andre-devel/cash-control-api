## Phase 11 — LGPD, Privacy & Data Retention

**Objective:** Implement all LGPD-mandated controls: consent capture, soft-delete, anonymization readiness, and token retention cleanup.

**Dependencies:** Phase 6 complete.

**Complexity:** Medium

### Phase 11.1 — Consent Capture

**Implementation Tasks:**

- [ ] `RegisterRequest.consentAccepted` → `@AssertTrue` (already defined in Phase 7.1)
- [ ] In `AuthService.register()`: persist `UserConsent` record with `consentVersion` from `AppProperties`, masked IP, truncated user-agent, `acceptedAt = now()`
- [ ] Also update denormalized `users.consentAcceptedAt` and `users.consentVersion`
- [ ] Record `CONSENT_ACCEPTED` audit event with `consentVersion` in metadata
- [ ] Create `GET /api/v1/users/me/consents` endpoint → returns consent history for own account

**Acceptance Criteria:**
- [ ] Registration without `consentAccepted: true` returns 400
- [ ] `UserConsent` record persisted on every registration with correct `consentVersion`
- [ ] Audit event `CONSENT_ACCEPTED` recorded with version in metadata

**Automated Tests:**
- [ ] `ConsentCaptureTest` — registration without consent fails; with consent persists `UserConsent`

---

### Phase 11.2 — Token Retention Cleanup

**Implementation Tasks:**

- [ ] Create `TokenRetentionService.java @Scheduled`:
  - `purgeExpiredPasswordResetTokens()` — deletes consumed tokens older than `retentionDays` from `password_reset_tokens`
  - `purgeExpiredVerificationTokens()` — deletes consumed/invalidated tokens past retention
  - Scheduled via `@Scheduled(cron = "0 0 2 * * *")` (2 AM daily)
  - Purge operations recorded as audit events
  - Batch delete to avoid large transaction locks
- [ ] Enable `@EnableScheduling` in configuration
- [ ] Expose purge configuration: `app.retention.password-reset-days`, `app.retention.verification-token-days`

**Acceptance Criteria:**
- [ ] Consumed tokens older than retention threshold are deleted on schedule
- [ ] Purge does not delete tokens still within retention window
- [ ] Purge event itself recorded in `audit_logs`

**Automated Tests:**
- [ ] `TokenRetentionServiceTest` — insert expired consumed tokens; run purge; assert deleted; assert non-expired tokens preserved

---

### Phase 11.3 — Anonymization Pipeline Scaffold

**Implementation Tasks:**

- [ ] Create `AnonymizationService.java` (scaffold, not yet triggered by API):
  - `void anonymizeUser(UUID userId)` — zeroes `email`, `display_name`, `password_hash`, sets `anonymized_at = now()`
  - Validates: user must already be soft-deleted (`deleted_at IS NOT NULL`)
  - UUID row preserved; all `audit_logs` FK references remain valid
  - Records audit event with `anonymized_at` timestamp in metadata
- [ ] This service is not wired to an API endpoint in this phase — it is the foundation for a future LGPD erasure request endpoint

**Acceptance Criteria:**
- [ ] Anonymized user's email, displayName, passwordHash are null or zeroed
- [ ] UUID row still exists — audit trail FK integrity preserved
- [ ] Cannot anonymize a non-soft-deleted user

**Automated Tests:**
- [ ] `AnonymizationServiceTest` — anonymize soft-deleted user; assert PII fields null; assert UUID preserved; assert non-deleted user throws exception

---

