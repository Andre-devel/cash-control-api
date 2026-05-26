## Phase 11 — Overdue Detection & Scheduled Jobs

**Objective:** Implement automatic overdue status transitions and recurring transaction instance generation as scheduled background tasks.

**Dependencies:** Phases 5, 7 complete.

**Complexity:** Low

### Phase 11.1 — Scheduled Services

**Implementation Tasks:**

- [ ] Create `OverdueDetectionScheduler.java` — `@Scheduled(cron = "0 0 1 * * *")` (daily at 01:00):
  - Calls `TransactionService.detectOverdue()` for all users
  - Transitions `PENDING` → `OVERDUE` where `paymentDate < today`
  - Logs count of transitions; never logs financial content
- [ ] Create `RecurrenceGenerationScheduler.java` — `@Scheduled(cron = "0 0 2 * * *")` (daily at 02:00):
  - Generates upcoming instances for all active recurrence rules whose `nextOccurrenceDate ≤ today + lookahead`
  - Updates `nextOccurrenceDate` after generation

**Acceptance Criteria:**
- [ ] Overdue detection runs daily; only affects `PENDING` with `paymentDate < today`
- [ ] Recurrence generation is idempotent (does not create duplicates if run twice)

**Automated Tests:**
- [ ] `OverdueDetectionSchedulerTest` — asserts correct transitions for a seeded dataset
- [ ] `RecurrenceGenerationSchedulerTest` — asserts no duplicates on double-run

---

