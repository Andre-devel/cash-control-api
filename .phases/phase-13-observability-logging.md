## Phase 13 — Observability & Logging

**Objective:** Implement structured JSON logging with correlation IDs. Financial data must never appear in logs.

**Dependencies:** Phase 3 complete.

**Complexity:** Low

### Phase 13.1 — Structured Logging Setup

**Implementation Tasks:**

- [ ] Add `logstash-logback-encoder` to `build.gradle.kts`
- [ ] Create `logback-spring.xml`:
  - JSON appender for production (`!local` profile)
  - Console appender for local development
  - Include `correlationId` MDC field in all log lines
- [ ] Create `LogSanitizationGuard.java` — utility that enforces no financial content in logs (verifiable via code review; enforced by convention)
- [ ] Update `CorrelationIdFilter.java` to set `MDC.put("correlationId", id)` on each request
- [ ] Log only: event type, user UUID, resource UUID, correlation ID, HTTP method, path, status code, duration
- [ ] Never log: amounts, descriptions, account names, category names, tag values

**Acceptance Criteria:**
- [ ] All log lines in production contain `correlationId`
- [ ] No financial content (amounts, descriptions) in any log output at any level
- [ ] Log format is JSON in non-local profiles

**Automated Tests:**
- [ ] `CorrelationIdFilterTest` — asserts `X-Correlation-ID` is present in the response header

---

