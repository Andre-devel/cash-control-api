## Phase 0 — Project Bootstrap & Build Infrastructure

**Objective:** Produce a compilable, runnable Spring Boot application skeleton with all dependencies declared, configuration externalized, and the test harness bootstrapped.

**Dependencies:** None — this is the starting point.

**Complexity:** Low

### Phase 0.1 — Gradle Build Configuration

**Implementation Tasks:**

- [x] Create `settings.gradle.kts` — set `rootProject.name = "java-auth-template"`
- [x] Create `build.gradle.kts` with the following dependency blocks:
  - `org.springframework.boot` plugin version `4.0.6`
  - `io.spring.dependency-management` plugin
  - `java` plugin targeting Java 25 (toolchain `JavaLanguageVersion.of(25)`)
  - Spring Boot Starter Web
  - Spring Boot Starter Security
  - Spring Boot Starter Data JPA
  - Spring Boot Starter Validation
  - Spring Boot Starter OAuth2 Client
  - Spring Boot Starter Mail
  - Spring Boot Starter Actuator
  - `spring-security-oauth2-jose` (JWT support)
  - `org.flywaydb:flyway-core` + `flyway-database-postgresql`
  - `org.postgresql:postgresql`
  - Lombok + annotation processor
  - `io.jsonwebtoken:jjwt-api` + `jjwt-impl` + `jjwt-jackson` (v0.12.6)
  - `org.springframework.boot:spring-boot-starter-test` (test scope)
  - `org.testcontainers:postgresql` (test scope)
  - `org.testcontainers:junit-jupiter` (test scope)
  - `org.springdoc:springdoc-openapi-starter-webmvc-ui` (v2.8.8)
  - `org.bouncycastle:bcprov-jdk18on` (v1.80, Argon2id support)
  - `net.logstash.logback:logstash-logback-encoder` (v8.0, JSON logging)
- [x] Configure `compileJava.options.annotationProcessorPath` for Lombok
- [x] Configure `test { useJUnitPlatform() }`
- [x] Create `gradle/wrapper/gradle-wrapper.properties` targeting Gradle 9.5.1 (upgraded from 8.x — Gradle 8.13 does not parse Java 25 version strings in its embedded Kotlin compiler)
- [x] Verify `./gradlew build` succeeds on an empty source set

**Acceptance Criteria:**
- [x] `./gradlew dependencies` resolves without conflicts
- [x] `./gradlew compileJava` succeeds
- [x] `./gradlew test` runs with zero tests and exits 0
- [x] No dependency version conflicts in the resolution graph

**Automated Tests:**
- [x] Gradle build smoke test (CI step — not a JUnit test)

---

### Phase 0.2 — Application Entry Point & Package Structure

**Implementation Tasks:**

- [x] Create package root: `com.example.auth`
- [x] Create `AuthApplication.java` with `@SpringBootApplication`
- [x] Establish enforced package structure:
  ```
  com.example.auth
  ├── config/          — Spring configuration classes
  ├── controller/      — @RestController classes only
  ├── service/         — Business logic interfaces + implementations
  ├── repository/      — Spring Data JPA interfaces
  ├── domain/
  │   ├── entity/      — JPA @Entity classes
  │   └── exception/   — Domain-specific exceptions
  ├── dto/
  │   ├── request/     — Inbound API DTOs (@Valid targets)
  │   └── response/    — Outbound API DTOs
  ├── security/        — JWT filter, UserDetailsService, SecurityConfig
  ├── util/            — Masking, sanitization, correlation ID utilities
  └── audit/           — Audit event service and taxonomy
  ```
- [x] Create `src/main/resources/application.yml` with environment-variable-bound placeholders (no secrets)
- [x] Create `src/main/resources/application-dev.yml` for local development overrides
- [x] Create `.env.example` documenting all required environment variables with descriptions but no values
- [x] Create `.gitignore` excluding: `.env`, `*.jar`, `build/`, `.idea/`, `*.class`

**Acceptance Criteria:**
- [x] Application starts with `./gradlew bootRun` against a PostgreSQL instance
- [x] Application fails fast with a clear error when required env vars (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`) are absent
- [x] No secrets in `application.yml` or any tracked file

**Automated Tests:**
- [x] `AuthApplicationTest` — `@SpringBootTest` context load test (fails if beans wire incorrectly)

---

### Phase 0.3 — Application Configuration (application.yml)

**Implementation Tasks:**

- [x] Configure datasource via environment variables:
  ```yaml
  spring.datasource.url: ${DB_URL}
  spring.datasource.username: ${DB_USERNAME}
  spring.datasource.password: ${DB_PASSWORD}
  spring.datasource.driver-class-name: org.postgresql.Driver
  ```
- [x] Configure JPA: `ddl-auto: validate`, `show-sql: false`, `dialect: PostgreSQLDialect`
- [x] Configure Flyway: `enabled: true`, `locations: classpath:db/migration`, `baseline-on-migrate: false`
- [x] Configure JWT properties block:
  ```yaml
  app.jwt.secret: ${JWT_SECRET}
  app.jwt.expiration-minutes: ${JWT_EXPIRATION_MINUTES:15}
  ```
- [x] Configure brute-force properties:
  ```yaml
  app.security.max-failed-attempts: ${MAX_FAILED_ATTEMPTS:5}
  app.security.lockout-duration-minutes: ${LOCKOUT_DURATION_MINUTES:15}
  ```
- [x] Configure password reset and verification TTLs:
  ```yaml
  app.security.password-reset-expiry-minutes: ${PASSWORD_RESET_EXPIRY_MINUTES:60}
  app.security.email-verification-expiry-hours: ${EMAIL_VERIFICATION_EXPIRY_HOURS:24}
  ```
- [x] Configure mail properties via environment variables
- [x] Configure OAuth2 Google client:
  ```yaml
  spring.security.oauth2.client.registration.google.client-id: ${GOOGLE_CLIENT_ID}
  spring.security.oauth2.client.registration.google.client-secret: ${GOOGLE_CLIENT_SECRET}
  spring.security.oauth2.client.registration.google.scope: openid,email,profile
  ```
- [x] Create `AppProperties.java` — `@ConfigurationProperties(prefix = "app")` bean for type-safe config access
- [x] Configure actuator: expose `health`, `info` only; disable all others

**Acceptance Criteria:**
- [x] All sensitive values bound from environment variables; none hardcoded
- [x] Application startup fails with descriptive `ConfigurationPropertiesBindException` when required properties are absent
- [x] `AppProperties` bean is available for injection in all service classes

**Automated Tests:**
- [x] `AppPropertiesTest` — verifies property binding from test environment

---

### Phase 0.4 — Test Infrastructure Setup

**Implementation Tasks:**

- [x] Create `src/test/resources/application-test.yml`:
  - Override datasource to use Testcontainers dynamic URL
  - Set `flyway.enabled: true` so migrations run in tests
  - Set `ddl-auto: validate`
  - Use a fixed test JWT secret
- [x] Create `PostgresTestContainerConfig.java`:
  - `@TestConfiguration`
  - Starts `PostgreSQLContainer<>` once per test suite (static instance)
  - Registers `DataSource` bean pointing to container
- [x] Create `BaseIntegrationTest.java`:
  - `@SpringBootTest(webEnvironment = RANDOM_PORT)`
  - `@ActiveProfiles("test")`
  - Imports `PostgresTestContainerConfig`
  - Provides shared `TestRestTemplate` and `MockMvc`
- [x] Create `BaseRepositoryTest.java`:
  - `@DataJpaTest`
  - Uses Testcontainers PostgreSQL
  - Does not load the full application context
- [x] Verify Testcontainers starts PostgreSQL and Flyway runs all migrations cleanly

**Acceptance Criteria:**
- [x] `BaseIntegrationTest` subclasses start the full application context against a real PostgreSQL container
- [x] Flyway migrations run automatically in test context
- [x] Container is reused across tests in the same JVM (static initialization)

**Automated Tests:**
- [x] `TestContainerSmokeTest` — asserts the PostgreSQL container starts and accepts a connection

---

