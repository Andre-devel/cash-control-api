## Phase 2 — Domain Layer (JPA Entities)

**Objective:** Implement all JPA entities mapped to the Flyway-managed schema. Entities must validate against the schema via `ddl-auto: validate`.

**Dependencies:** Phase 1 complete (all migrations applied).

**Complexity:** Medium

### Phase 2.1 — Lookup Table Entities

**Implementation Tasks:**

- [x] Create `AccountStatus.java` entity: fields matching `account_statuses` table; no setters on `id`, `slug`
- [x] Create `AuthOrigin.java` entity
- [x] Create `OauthProvider.java` entity
- [x] Create `LockoutType.java` entity
- [x] Create `PermissionCategory.java` entity
- [x] Create `AuthenticationMethod.java` entity
- [x] Create `AuditEventType.java` entity (include `category`, `severity` fields)
- [x] Create `AuditOutcome.java` entity
- [x] All lookup entities: `@Table`, `@Column` annotations matching exact column names; Lombok `@Getter`, `@NoArgsConstructor`
- [x] Create `LookupEntityRepository` marker interface for common lookup queries

**Acceptance Criteria:**
- [x] `ddl-auto: validate` passes without schema drift errors for all lookup tables
- [x] Lombok-generated equals/hashCode based on `id` only (UUID PK)

**Automated Tests:**
- [x] `LookupEntityRepositoryTest` — `@DataJpaTest`, asserts `findBySlug()` returns seeded values for each lookup entity

---

### Phase 2.2 — Core Identity Entity

**Implementation Tasks:**

- [x] Create `User.java` entity:
  - `@Entity @Table(name = "users")`
  - All fields with exact `@Column` names
  - `@ManyToOne` to `AccountStatus`, `AuthOrigin`, `LockoutType`
  - `@CreationTimestamp` on `createdAt`, `@UpdateTimestamp` on `updatedAt`
  - `credentials_updated_at` as `Instant` — `@Column(nullable = false)`
  - `deletedAt` as nullable `Instant`
  - `@ToString.Exclude` on `passwordHash` — Lombok must never include it in toString
  - `@JsonIgnore` equivalent: `passwordHash` excluded from all serialization paths
  - No `@OneToMany` collection on `User` — avoid N+1; use separate repositories
- [x] Create `UserSlugConstants.java` — constants for account status slugs and auth origin slugs used in service layer comparisons

**Acceptance Criteria:**
- [x] `ddl-auto: validate` passes for `users` table
- [x] `passwordHash` field has NO getter accessible from outside `security` package (package-private getter pattern or service-layer-only access)
- [x] `toString()` output never includes `passwordHash`

**Automated Tests:**
- [x] `UserEntityTest` — asserts `toString()` does not contain `passwordHash`
- [x] `UserRepositoryTest` — `@DataJpaTest`, `findByEmailAndDeletedAtIsNull()`, `findById()`, optimistic locking test

---

### Phase 2.3 — RBAC Entities

**Implementation Tasks:**

- [x] Create `Role.java` entity: `@ManyToOne` to `User` for `createdById` (nullable)
- [x] Create `Permission.java` entity: `@ManyToOne` to `PermissionCategory`
- [x] Create `RolePermission.java` entity: composite business key `(roleId, permissionId)`, `grantedById` FK nullable
- [x] Create `UserRole.java` entity: composite business key `(userId, roleId)`, `expiresAt` nullable
- [x] Create `UserPermission.java` entity: composite business key `(userId, permissionId)`, `expiresAt` nullable

**Acceptance Criteria:**
- [x] Unique constraints on join tables enforced at entity level via `@Table(uniqueConstraints = ...)`
- [x] No cascade deletes from Role/Permission to join tables
- [x] `ddl-auto: validate` passes for all 5 RBAC tables

**Automated Tests:**
- [x] `RoleRepositoryTest` — asserts `findByNameIgnoreCase()`, duplicate name throws `DataIntegrityViolationException`
- [x] `UserRoleRepositoryTest` — asserts idempotent insert (duplicate throws `DataIntegrityViolationException`)

---

### Phase 2.4 — Token, OAuth2, Brute Force, Audit & Privacy Entities

**Implementation Tasks:**

- [x] Create `EmailVerificationToken.java` entity: `newEmail` nullable, `consumedAt` and `invalidatedAt` nullable `Instant`
- [x] Create `PasswordResetToken.java` entity: `ipAddressMasked`, `consumedAt`, `invalidatedAt`
- [x] Create `OauthAccount.java` entity: `@ManyToOne` to `User` and `OauthProvider`; `unlinkedAt` nullable
- [x] Create `LoginAttempt.java` entity: `userId` nullable UUID (not a FK-mapped relation), `@ManyToOne` to `AuthenticationMethod`; `failureContext` — internal field, `@JsonIgnore`
- [x] Create `AccountLockout.java` entity: `lockedById` FK nullable, `expiresAt` nullable
- [x] Create `AuditLog.java` entity: `metadata` as `Map<String, Object>` mapped with `@Type(JsonType.class)` or `@JdbcTypeCode(SqlTypes.JSON)`; NO `updatedAt`
- [x] Create `UserConsent.java` entity: `revokedAt`, `revocationReason` nullable
- [x] Create `MfaConfiguration.java` entity (inactive scaffold — no service wired)

**Acceptance Criteria:**
- [x] `AuditLog` entity has no `updatedAt` field — enforces append-only
- [x] `LoginAttempt.userId` is a plain `UUID` field (not `@ManyToOne`) — supports null for unknown-email attempts
- [x] `ddl-auto: validate` passes for all tables after all 8 entity classes are present

**Automated Tests:**
- [x] `AuditLogRepositoryTest` — asserts `findByTargetUserIdOrderByCreatedAtDesc(UUID)` returns correct ordering
- [x] `PasswordResetTokenRepositoryTest` — asserts `findByTokenHashAndConsumedAtIsNullAndInvalidatedAtIsNull()` query

---

### Phase 2.5 — Domain Exceptions

**Implementation Tasks:**

- [x] Create `AuthException.java` — base unchecked exception
- [x] Create `InvalidCredentialsException.java extends AuthException` — generic auth failure (anti-enumeration)
- [x] Create `AccountLockedException.java extends AuthException`
- [x] Create `AccountNotVerifiedException.java extends AuthException`
- [x] Create `AccountDisabledException.java extends AuthException`
- [x] Create `AccountDeletedException.java extends AuthException`
- [x] Create `TokenExpiredException.java extends AuthException`
- [x] Create `TokenAlreadyConsumedException.java extends AuthException`
- [x] Create `EmailAlreadyExistsException.java extends AuthException` — internal use only; never returned raw to client
- [x] Create `ResourceNotFoundException.java extends AuthException`
- [x] Create `ConflictException.java extends AuthException`
- [x] Create `PermissionDeniedException.java extends AuthException` — thrown when `@PreAuthorize` not sufficient
- [x] Create `OAuthProviderException.java extends AuthException`

**Acceptance Criteria:**
- [x] All exceptions are unchecked (`extends RuntimeException` chain)
- [x] No exception exposes a user-existence hint in its message by default
- [x] All exceptions include a `correlationId` field populated at throw site

**Automated Tests:**
- [x] `DomainExceptionTest` — unit tests asserting exception hierarchy and message formatting

---

