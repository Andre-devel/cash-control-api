## Phase 7 — Recurring Transactions

**Objective:** Implement recurrence rules, instance generation, pausing, and deletion strategies.

**Dependencies:** Phase 5 complete.

**Complexity:** Medium

### Phase 7.1 — Recurrence Service

**Implementation Tasks:**

- [ ] Create `RecurrenceRepository.java`
- [ ] Create `RecurrenceService.java` and `RecurrenceServiceImpl.java`:
  - `createRecurrence(CreateRecurrenceRequest, UUID userId)`:
    - Creates `RecurrenceRule`
    - Generates first instance immediately
    - Pre-generates instances for the next N periods (configurable) or marks `nextOccurrenceDate` for lazy generation
  - `editSeries(UUID ruleId, EditRecurrenceRequest, UUID userId)` — updates future `PENDING` instances; updates master rule
  - `pauseRecurrence(UUID ruleId, PauseRequest, UUID userId)` — sets `status = PAUSED`; cancels future `PENDING` instances for the pause window
  - `resumeRecurrence(UUID ruleId, UUID userId)` — restores `ACTIVE` status; regenerates instances from resume date
  - `deleteRecurrence(UUID ruleId, DeleteRecurrenceStrategy, UUID userId)`:
    - `FUTURE_ONLY`: cancels future `PENDING` instances; soft-deletes rule
    - `ALL`: cancels all `PENDING` instances; soft-deletes rule
    - Never touches `PAID` instances
- [ ] Create `RecurrenceGeneratorService.java` — stateless utility that computes the next occurrence date given a frequency and a base date

**Acceptance Criteria:**
- [ ] `PAID` instances never modified by any recurrence operation
- [ ] Pause cancels exactly the pending instances in the pause window
- [ ] `RecurrenceGeneratorService` handles month-end edge cases (e.g., Jan 31 → Feb 28)

**Automated Tests:**
- [ ] `RecurrenceGeneratorServiceTest` — edge cases for all frequencies including month-end dates
- [ ] `RecurrenceServiceIntegrationTest` — full lifecycle

---

### Phase 7.2 — Recurrence Controller

**Implementation Tasks:**

- [ ] Create `RecurrenceController.java` — `@RestController @RequestMapping("/api/v1/recurrences")`:
  - `POST /` → 201
  - `PUT /{id}` → edit series → 200
  - `POST /{id}/pause` → 200
  - `POST /{id}/resume` → 200
  - `DELETE /{id}` → (strategy as query param) → 200

**Automated Tests:**
- [ ] `RecurrenceControllerTest` — HTTP validation

---

