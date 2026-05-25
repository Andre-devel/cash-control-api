## Phase 12 — Observability & Structured Logging

**Objective:** Implement structured JSON logging, MDC correlation ID propagation, and Spring Actuator endpoints.

**Dependencies:** Phase 5 (MDC/Correlation ID) complete.

**Complexity:** Low

### Phase 12.1 — Structured JSON Logging

**Implementation Tasks:**

- [ ] Add `net.logstash.logback:logstash-logback-encoder` dependency
- [ ] Create `src/main/resources/logback-spring.xml`:
  - Production profile: `LogstashEncoder` for JSON output
  - Dev profile: `ConsoleAppender` with readable pattern
  - Include MDC fields in every log entry: `correlationId`, `userId` (when authenticated)
  - Set log levels via environment variable: `${LOG_LEVEL_ROOT:INFO}`, `${LOG_LEVEL_APP:DEBUG}`
- [ ] Configure `LogstashEncoder` custom fields: `appName`, `environment`
- [ ] Log sensitive field blocklist: verify no log appender outputs `password`, `token`, `secret`, `hash` at any log level

**Acceptance Criteria:**
- [ ] Every log line in production is valid JSON
- [ ] `correlationId` present in every log line for a request
- [ ] No password, token, or secret value appears in any log at DEBUG level or above

**Automated Tests:**
- [ ] `StructuredLoggingTest` — capture log output during a request; assert JSON parseable; assert `correlationId` present; assert no sensitive field names contain values

---

### Phase 12.2 — Actuator Health Endpoints

**Implementation Tasks:**

- [ ] Configure Actuator: expose `health` and `info` only
- [ ] Add custom `HealthIndicator` for DB connectivity check
- [ ] `GET /actuator/health` → `{ "status": "UP" }` when DB is reachable
- [ ] `GET /actuator/health/liveness` and `/readiness` for Kubernetes probes
- [ ] Disable all other actuator endpoints in production

**Acceptance Criteria:**
- [ ] `GET /actuator/health` returns 200 with `status: UP` when application is healthy
- [ ] Returns 503 when DB is unreachable
- [ ] No sensitive information exposed via actuator

**Automated Tests:**
- [ ] `ActuatorHealthTest` — `GET /actuator/health` returns 200; `GET /actuator/env` returns 404 (disabled)

---

