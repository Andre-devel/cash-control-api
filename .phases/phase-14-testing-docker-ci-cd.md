## Phase 14 — Testing, Docker & CI/CD

**Objective:** Complete the test suite covering all domain rules, containerize the application, and configure CI/CD.

**Dependencies:** All previous phases complete.

**Complexity:** Medium

### Phase 14.1 — Comprehensive Test Suite

**Implementation Tasks:**

- [ ] Verify unit test coverage for all service methods
- [ ] Verify integration tests for each major domain: accounts, transactions, installments, recurrences, categories, credit cards, dashboard
- [ ] Add boundary tests:
  - `BigDecimal` precision in installment splits (various total/count combinations)
  - Balance consistency after concurrent-scenario transaction sequences
  - Overdue detection edge cases (same day, past day, future day)
  - Invoice cycle edge cases (month boundary with `closingDay = 31`)
- [ ] Verify all integration tests use Testcontainers PostgreSQL (no mocked repositories for core financial flows)
- [ ] Verify no `double` or `float` used for monetary values anywhere in the codebase (`./gradlew test` includes a custom lint check or inspection)

**Acceptance Criteria:**
- [ ] All tests pass on `./gradlew test`
- [ ] Integration tests hit a real PostgreSQL container
- [ ] No financial floating-point arithmetic anywhere in the production code

**Automated Tests:**
- [ ] Full test suite — all unit and integration tests

---

### Phase 14.2 — Docker & Docker Compose

**Implementation Tasks:**

- [ ] Create `Dockerfile` — multi-stage build:
  - Stage 1 (`builder`): `gradle:jdk25` — runs `./gradlew bootJar`
  - Stage 2 (`runtime`): `eclipse-temurin:25-jre-alpine` — copies JAR from builder
  - Expose port 8080; non-root user; health check via `/actuator/health`
- [ ] Create `.dockerignore` — exclude: `.git`, `build/`, `.gradle/`, `*.md`, `.env`
- [ ] Create `docker-compose.yml`:
  - `app` service: builds from `Dockerfile`; depends on `postgres`; env vars from `.env`
  - `postgres` service: `postgres:18`; volume for data persistence; health check
- [ ] Create `docker-compose.override.yml` for local development overrides

**Acceptance Criteria:**
- [ ] `docker compose up` starts both services and application is healthy at `/actuator/health`
- [ ] All configuration injected via environment variables; no secrets in Docker files

**Automated Tests:**
- [ ] Docker build smoke test in CI

---

### Phase 14.3 — CI/CD Pipeline

**Implementation Tasks:**

- [ ] Create `.github/workflows/ci.yml`:
  - Trigger: push to `main`, pull requests to `main`
  - Job `build-and-test`:
    - `actions/checkout@v4`
    - `actions/setup-java@v4` with Java 25 and Gradle cache
    - `./gradlew build` — compile and run all tests (Testcontainers spins up PostgreSQL in CI)
    - Upload test reports as artifacts on failure
  - Job `security-check`:
    - OWASP Dependency Check or equivalent
  - Job `docker-build`:
    - Build Docker image; do not push unless on `main` tag
- [ ] Ensure CI does not require any external secrets beyond the test JWT key

**Acceptance Criteria:**
- [ ] PR build fails if any test fails
- [ ] PR build fails if Docker build fails
- [ ] Test reports are available as CI artifacts

**Automated Tests:**
- [ ] All CI jobs green on a clean branch

---

### Phase 14.4 — Verification

**Implementation Tasks:**

- [ ] Run `./gradlew test` — all tests pass
- [ ] Run `docker compose up --build` — application starts; `/actuator/health` returns `UP`
- [ ] Smoke-test all major endpoints via Insomnia or curl:
  - Create account → adjust balance → create transaction → create installment series → create recurrence → create credit card → record charge → pay invoice → dashboard overview
- [ ] Verify no `double`/`float` arithmetic for monetary values (`grep -r "double\|float" src/main/java` returns zero hits in financial logic)
- [ ] Verify no financial content in logs during the smoke test run
- [ ] Confirm all Flyway migrations apply cleanly on a fresh PostgreSQL 18 container

**Acceptance Criteria:**
- [ ] All tests pass
- [ ] Docker smoke test passes
- [ ] All major flows work end-to-end
- [ ] Zero floating-point monetary arithmetic

---

*End of Implementation Roadmap — cash-control-api v1*
