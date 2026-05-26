## Phase 0 — Project Bootstrap & Build Infrastructure

**Objective:** Produce a compilable, runnable Spring Boot application skeleton with all dependencies declared, configuration externalized, and the test harness bootstrapped.

**Dependencies:** None — this is the starting point.

**Complexity:** Low

### Phase 0.1 — Gradle Build Configuration

**Implementation Tasks:**

- [x] Create `settings.gradle.kts` — set `rootProject.name = "cash-control-api"`
- [x] Create `build.gradle.kts` with the following dependency blocks:
  - `org.springframework.boot` plugin version `4.0.6`
  - `io.spring.dependency-management` plugin
  - `java` plugin targeting Java 25 (toolchain `JavaLanguageVersion.of(25)`)
  - Spring Boot Starter Web
  - Spring Boot Starter Security
  - Spring Boot Starter Data JPA
  - Spring Boot Starter Validation
  - Spring Boot Starter Actuator
  - `org.flywaydb:flyway-core` + `flyway-database-postgresql`
  - `org.postgresql:postgresql`
  - Lombok + annotation processor
  - `org.springframework.boot:spring-boot-starter-test` (test scope)
  - `org.testcontainers:postgresql` (test scope)
  - `org.testcontainers:junit-jupiter` (test scope)
  - `org.springdoc:springdoc-openapi-starter-webmvc-ui` (v2.8.8)
  - `net.logstash.logback:logstash-logback-encoder` (v8.0, JSON logging)
- [x] Configure `compileJava.options.annotationProcessorPath` for Lombok
- [x] Configure `test { useJUnitPlatform() }`
- [x] Create `gradle/wrapper/gradle-wrapper.properties` targeting Gradle 9.5.1
- [x] Verify `./gradlew build` succeeds on an empty source set

**Acceptance Criteria:**
- [x] `./gradlew dependencies` resolves without conflicts
- [x] `./gradlew compileJava` succeeds
- [x] `./gradlew test` runs with zero tests and exits 0

**Automated Tests:**
- [x] Gradle build smoke test (CI step — not a JUnit test)

---

### Phase 0.2 — Application Entry Point & Package Structure

**Implementation Tasks:**

- [x] Create package root: `com.cashcontrol.api` (adapted from spec; auth module already established this)
- [x] Create `AuthApplication.java` with `@SpringBootApplication` (serves as project entry point)
- [x] Establish enforced package structure:
  ```
  com.cashcontrol.api
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
  ├── security/        — JWT filter, SecurityConfig
  └── util/            — Sanitization, correlation ID utilities
  ```
- [x] Create `src/main/resources/application.yml` with environment-variable-bound placeholders (no secrets)
- [x] Create `.env.example` documenting all required environment variables
- [x] Create `.gitignore` excluding: `.env`, `*.jar`, `build/`, `.idea/`, `*.class`

**Acceptance Criteria:**
- [x] Application starts with `./gradlew bootRun` against a PostgreSQL instance
- [x] Application fails fast with a clear error when required env vars (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`) are absent
- [x] No secrets in `application.yml` or any tracked file

**Automated Tests:**
- [x] `AuthApplicationTest` — `@SpringBootTest` context load test

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
- [x] Configure JWT validation properties block (public key or secret from auth module):
  ```yaml
  app.jwt.secret: ${JWT_SECRET}
  ```
- [x] Configure attachment settings:
  ```yaml
  app.attachments.max-file-size-mb: ${ATTACHMENT_MAX_SIZE_MB:10}
  app.attachments.max-per-transaction: ${ATTACHMENT_MAX_PER_TRANSACTION:5}
  app.attachments.allowed-types: pdf,png,jpg,jpeg
  ```
- [x] Configure upcoming bills default window:
  ```yaml
  app.dashboard.upcoming-bills-days: ${UPCOMING_BILLS_DAYS:7}
  app.dashboard.upcoming-bills-max-results: ${UPCOMING_BILLS_MAX_RESULTS:20}
  ```
- [x] Configure actuator: expose `health`, `info` only
- [x] Create `AppProperties.java` — `@ConfigurationProperties(prefix = "app")` bean with Attachments and Dashboard inner classes

**Acceptance Criteria:**
- [x] All sensitive values bound from environment variables; none hardcoded
- [x] Application startup fails with descriptive error when required properties are absent
- [x] `AppProperties` bean is available for injection in all service classes

**Automated Tests:**
- [x] `AppPropertiesTest` — verifies property binding from test environment

---

### Phase 0.4 — Test Infrastructure Setup

**Implementation Tasks:**

- [x] Create `src/test/resources/application-test.yml`:
  - Override datasource to use Testcontainers dynamic URL
  - Set `flyway.enabled: true`
  - Set `ddl-auto: validate`
  - Use a fixed test JWT secret
- [x] Create `PostgresTestContainerConfig.java`:
  - `@TestConfiguration`
  - Static `PostgreSQLContainer<>` instance (shared across tests)
  - Registers `DataSource` bean pointing to the container
- [x] Create `BaseIntegrationTest.java`:
  - `@SpringBootTest(webEnvironment = RANDOM_PORT)`
  - `@ActiveProfiles("test")`
  - Imports `PostgresTestContainerConfig`
  - Provides shared `MockMvc` setup
- [x] Create `BaseRepositoryTest.java`:
  - `@SpringBootTest` with Testcontainers PostgreSQL
- [x] Verify Testcontainers starts PostgreSQL and Flyway runs all migrations cleanly

**Acceptance Criteria:**
- [x] `BaseIntegrationTest` subclasses start the full context against a real PostgreSQL container
- [x] Flyway migrations run automatically in test context
- [x] Container is reused across tests in the same JVM

**Automated Tests:**
- [x] `TestContainerSmokeTest` — asserts the PostgreSQL container starts and accepts a connection

---

