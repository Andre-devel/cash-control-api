## Phase 15 — Docker & CI/CD Readiness

**Objective:** Produce Docker artifacts and CI/CD pipeline configuration for production deployment readiness.

**Dependencies:** All phases complete.

**Complexity:** Low

### Phase 15.1 — Dockerfile (Multi-Stage Build)

**Implementation Tasks:**

- [ ] Create `Dockerfile`:
  ```dockerfile
  # Stage 1: Build
  FROM eclipse-temurin:25-jdk-alpine AS builder
  WORKDIR /app
  COPY gradlew settings.gradle.kts build.gradle.kts ./
  COPY gradle/ gradle/
  RUN ./gradlew dependencies --no-daemon
  COPY src/ src/
  RUN ./gradlew bootJar --no-daemon -x test

  # Stage 2: Runtime
  FROM eclipse-temurin:25-jre-alpine AS runtime
  RUN addgroup -S appgroup && adduser -S appuser -G appgroup
  WORKDIR /app
  COPY --from=builder /app/build/libs/*.jar app.jar
  USER appuser
  EXPOSE 8080
  ENTRYPOINT ["java", "-jar", "app.jar"]
  ```
- [ ] Add `.dockerignore` — exclude `.git`, `build/`, `.idea/`, `*.md`, `docs/`
- [ ] Verify image builds without secrets baked in
- [ ] Verify image size < 300 MB (JRE-only runtime stage)

**Acceptance Criteria:**
- [ ] `docker build -t java-auth-template .` succeeds
- [ ] Container starts with environment variables injected via `docker run -e`
- [ ] Container fails fast with clear message if `DB_URL` is absent
- [ ] No secrets in Dockerfile or image layers

---

### Phase 15.2 — Docker Compose (Local Development)

**Implementation Tasks:**

- [ ] Create `docker-compose.yml`:
  - `postgres` service: PostgreSQL 18, named volume, health check
  - `app` service: depends on `postgres`, environment variables from `.env`
  - Network isolation between services
- [ ] Create `docker-compose.override.yml` for local dev (hot-reload, debug port)
- [ ] Document startup sequence in `.env.example`
- [ ] `docker compose up` starts a working dev environment with Flyway migrations applied

**Acceptance Criteria:**
- [ ] `docker compose up -d` starts PostgreSQL and application
- [ ] Flyway migrations run automatically on app startup
- [ ] `GET /actuator/health` returns 200 within 30 seconds of container start

---

### Phase 15.3 — CI/CD Pipeline Configuration

**Implementation Tasks:**

- [ ] Create `.github/workflows/ci.yml`:
  - Trigger: push to `main`, pull request to `main`
  - Jobs:
    1. `build-and-test`:
       - Set up Java 25 (`eclipse-temurin`)
       - `./gradlew test` — Testcontainers starts PostgreSQL automatically
       - Publish test results as GitHub Actions summary
       - Cache Gradle dependencies
    2. `security-check`:
       - OWASP Dependency Check or `./gradlew dependencyCheckAnalyze`
       - Fail on CVSS ≥ 7.0
    3. `docker-build` (on `main` push only):
       - Build Docker image
       - Push to registry with `git sha` and `latest` tags
- [ ] Gate: PRs cannot merge without CI passing

**Acceptance Criteria:**
- [ ] Full test suite runs in CI without manual setup (Testcontainers handles PostgreSQL)
- [ ] `GOOGLE_CLIENT_SECRET`, `JWT_SECRET` etc. are GitHub secrets — never in workflow YAML
- [ ] CI runs on pull requests and blocks merge on failure

---

## Phase Summary

| Phase | Description | Complexity | Dependencies |
|---|---|---|---|
| **0** | Project Bootstrap & Build | Low | None |
| **0.1** | Gradle build configuration | Low | — |
| **0.2** | App entry point & package structure | Low | 0.1 |
| **0.3** | application.yml configuration | Low | 0.1 |
| **0.4** | Test infrastructure (Testcontainers) | Low | 0.1 |
| **1** | Database Migrations (Flyway) | Medium | Phase 0 |
| **1.1** | Flyway baseline config | Low | 0.3 |
| **1.2** | V1: Lookup tables | Medium | 1.1 |
| **1.3** | V2: Seed data | Low | 1.2 |
| **1.4** | V3: Core identity (users) | Medium | 1.3 |
| **1.5** | V4: RBAC schema | Medium | 1.4 |
| **1.6** | V5: Token tables | Low | 1.4 |
| **1.7** | V6: OAuth2, security, audit, privacy | Medium | 1.5, 1.6 |
| **1.8** | V7: RBAC seed data | Low | 1.5 |
| **2** | Domain Layer (JPA Entities) | Medium | Phase 1 |
| **2.1** | Lookup table entities | Low | 1.2, 1.3 |
| **2.2** | User entity | Medium | 1.4 |
| **2.3** | RBAC entities | Medium | 1.5 |
| **2.4** | Token, OAuth2, brute force, audit entities | Medium | 1.6, 1.7 |
| **2.5** | Domain exceptions | Low | — |
| **3** | Repository Layer | Low | Phase 2 |
| **3.1** | Core repositories | Low | 2.2, 2.3 |
| **3.2** | Token & security repositories | Low | 2.4 |
| **4** | Security Infrastructure | High | Phase 2, 3 |
| **4.1** | Password hashing (Argon2id) | Low | 0.1 |
| **4.2** | JWT service | Medium | 0.3, 2.2 |
| **4.3** | JWT authentication filter | High | 4.2, 3.1 |
| **4.4** | Custom UserDetailsService | Medium | 3.1 |
| **4.5** | Spring Security filter chain | High | 4.3, 4.4 |
| **4.6** | HTTP security headers & CORS | Low | 4.5 |
| **4.7** | Permission resolution | Medium | 3.1, 3.2 |
| **5** | Cross-Cutting Utilities | Low | Phase 2 |
| **5.1** | Data masking utilities | Low | — |
| **5.2** | Correlation ID & MDC | Low | — |
| **5.3** | Email service | Low | 0.3 |
| **6** | Service Layer | High | Phase 3, 4, 5 |
| **6.1** | Audit service | Medium | 3.2, 5.1, 5.2 |
| **6.2** | Authentication service | High | 4.1, 4.2, 4.7, 6.1 |
| **6.3** | Password reset service | Medium | 6.1, 5.3 |
| **6.4** | Email verification service | Medium | 6.1, 5.3 |
| **6.5** | User management service | Medium | 6.1 |
| **6.6** | RBAC service | Medium | 6.1, 3.1 |
| **6.7** | Admin security operations | Medium | 6.1, 6.5 |
| **7** | DTO Layer & Exception Handling | Medium | Phase 6 |
| **7.1** | Request DTOs | Low | 4.1 |
| **7.2** | Response DTOs | Low | — |
| **7.3** | Global exception handler (RFC 7807) | Medium | 2.5 |
| **8** | Controller Layer | Medium | Phase 7 |
| **8.1** | Authentication controller | Medium | 7.1, 7.2, 7.3 |
| **8.2** | User management controller | Low | 6.5 |
| **8.3** | RBAC controllers | Medium | 6.6 |
| **8.4** | Audit & admin security controllers | Low | 6.7 |
| **9** | OAuth2 Google Integration | High | Phase 6, 8 |
| **9.1** | OAuth2 success handler | High | 6.2, 4.2 |
| **9.2** | OAuth2 failure handler & unlink | Medium | 6.1 |
| **10** | Brute Force & Rate Limiting | Medium | Phase 6.2 |
| **10.1** | Login attempt tracking & lockout | Medium | 6.2 |
| **10.2** | Rate limiting filter | Medium | 4.5 |
| **11** | LGPD, Privacy & Data Retention | Medium | Phase 6 |
| **11.1** | Consent capture | Low | 6.2 |
| **11.2** | Token retention cleanup | Low | 3.2 |
| **11.3** | Anonymization pipeline scaffold | Medium | 6.5 |
| **12** | Observability & Structured Logging | Low | Phase 5 |
| **12.1** | Structured JSON logging (Logback) | Low | 5.2 |
| **12.2** | Actuator health endpoints | Low | 0.3 |
| **13** | OpenAPI Documentation | Low | Phase 8 |
| **14** | Comprehensive Test Suite | High | All phases |
| **14.1** | Unit tests | High | All |
| **14.2** | Repository tests | Medium | Phase 3 |
| **14.3** | Controller tests | Medium | Phase 8 |
| **14.4** | Integration tests | High | All |
| **14.5** | Security tests | High | Phase 4, 8 |
| **14.6** | Flyway migration validation | Medium | Phase 1 |
| **15** | Docker & CI/CD Readiness | Low | All phases |
| **15.1** | Dockerfile (multi-stage) | Low | All |
| **15.2** | Docker Compose (dev environment) | Low | 15.1 |
| **15.3** | CI/CD pipeline (GitHub Actions) | Low | All |

---

## Critical Security Invariants

These constraints must be verified at every phase where they are relevant:

| Invariant | Enforced By |
|---|---|
| `passwordHash` never in API responses | `@JsonIgnore` + DTO pattern (Phase 7.2) |
| Raw token never logged or stored | `DataMasker.sanitizeTokenValue()` + convention (Phase 5.1) |
| JWT `sub` = UUID, not email | `JwtService` + `JwtPayloadSecurityTest` (Phase 4.2, 14.5) |
| Anti-enumeration on all auth flows | `AuthService` + `AntiEnumerationTest` (Phase 6.2, 14.5) |
| `credentials_updated_at` updated on every credential-invalidating event | `AuthService`, `PasswordResetService`, `UserService` (Phase 6) |
| No ENUM types in DB | Lookup tables via Flyway (Phase 1.2) |
| `audit_logs` is append-only | No `updatedAt` on entity; no API update/delete endpoint (Phase 2.4, 8.4) |
| Soft-delete check before all auth operations | `AccountStatusChecker` (Phase 4.4) |
| `failure_context` stored internally, never returned | `LoginAttempt.failureContext` + `@JsonIgnore` (Phase 2.4) |
| Google OAuth2 access/refresh tokens not persisted | `OAuth2SuccessHandler` — discard after profile extraction (Phase 9.1) |
| CSRF disabled — JWT Bearer header is CSRF-safe | `SecurityConfig` (Phase 4.5) |
| Session creation disabled | `SessionCreationPolicy.STATELESS` (Phase 4.5) |
| All secrets from environment variables | `application.yml` binding + `AppProperties` (Phase 0.3) |

---

## Testing Checklist (Complete)

- [ ] All services: >85% line coverage via unit tests
- [ ] All auth flows covered by integration tests with real PostgreSQL (Testcontainers)
- [ ] JWT payload PII-free assertions automated in CI
- [ ] Anti-enumeration response shape equality tested for all auth endpoints
- [ ] Authorization matrix test covers all sensitive endpoints × all permission strings
- [ ] `credentials_updated_at` invalidation tested end-to-end (old JWT rejected after event)
- [ ] Brute-force lockout threshold tested in integration (5 failures → locked)
- [ ] OAuth2 flows tested with mocked provider (WireMock)
- [ ] Flyway migration test runs on CI against fresh PostgreSQL 18 container
- [ ] Security headers verified on all response types
- [ ] Rate limiting tested (N+1 requests → 429)
- [ ] Soft-delete filter tested (deleted users excluded from lookups)
- [ ] LGPD: consent required for registration tested
- [ ] LGPD: anonymization preserves UUID and audit FK integrity tested
- [ ] No test uses mocked repositories for core auth flows — Testcontainers only

---

## Risks & Technical Notes

| Risk | Mitigation |
|---|---|
| Argon2id parameters (memory=64MB) may cause OOM in low-memory CI runners | Use BCrypt fallback with cost=12 in test profile; Argon2id in production |
| PostgreSQL 18 may not have Testcontainers image at project start | Pin to `postgres:17` until official `postgres:18` image is available |
| Google OAuth2 flow requires network access in tests | Use WireMock to mock Google authorization server; no real OAuth2 in CI |
| JWT token size growth with many permissions | Monitor token size; consider permission grouping if authorities exceed 50 strings |
| `@Async` audit writes may be lost on application crash | Accept loss for now; Phase 15 CI/CD note documents this as known trade-off for MVP |
| Flyway baseline migration must be idempotent for team environments | Use `ON CONFLICT DO NOTHING` for all seed inserts |
| `credentials_updated_at` DB read on every authenticated request | Accepted — single indexed PK lookup; sub-millisecond latency; architectural constraint |
| Spring Boot 4.0.6 / Java 25 may have breaking API changes from Boot 3.x | Follow Spring Boot 4 migration guide; monitor deprecations in each phase |
