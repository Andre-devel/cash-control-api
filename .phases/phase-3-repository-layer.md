## Phase 3 — Repository Layer

**Objective:** Implement Spring Data JPA repositories with all query methods required by the service layer. No business logic in repositories.

**Dependencies:** Phase 2 complete.

**Complexity:** Low

### Phase 3.1 — Core Repositories

**Implementation Tasks:**

- [x] `UserRepository extends JpaRepository<User, UUID>`:
  - `Optional<User> findByEmailAndDeletedAtIsNull(String email)`
  - `Optional<User> findByIdAndDeletedAtIsNull(UUID id)`
  - `boolean existsByEmailAndDeletedAtIsNull(String email)`
  - `Page<User> findAllByDeletedAtIsNull(Pageable pageable)`
  - `Page<User> findAllByDeletedAtIsNullAndAccountStatusId(UUID statusId, Pageable pageable)`
- [x] `RoleRepository`: `Optional<Role> findByNameIgnoreCase(String name)`, `boolean existsByName(String name)`
- [x] `PermissionRepository`: `Optional<Permission> findByName(String name)`, `List<Permission> findByCategoryId(UUID categoryId)`
- [x] `RolePermissionRepository`: `List<RolePermission> findByRoleId(UUID roleId)`, `boolean existsByRoleIdAndPermissionId(UUID, UUID)`, `deleteByRoleIdAndPermissionId(UUID, UUID)`
- [x] `UserRoleRepository`: `List<UserRole> findByUserId(UUID userId)`, `boolean existsByUserIdAndRoleId(UUID, UUID)`, `deleteByUserIdAndRoleId(UUID, UUID)`
- [x] `UserPermissionRepository`: `List<UserPermission> findByUserId(UUID userId)`, `boolean existsByUserIdAndPermissionId(UUID, UUID)`, `deleteByUserIdAndPermissionId(UUID, UUID)`

**Acceptance Criteria:**
- [x] All query methods have derived query names or explicit `@Query` JPQL — no native queries unless unavoidable
- [x] No business logic or conditional branching in any repository interface or implementation

**Automated Tests:**
- [x] `UserRepositoryTest` (`@DataJpaTest`) — tests `findByEmailAndDeletedAtIsNull`, soft-delete filter, pagination
- [x] `RolePermissionRepositoryTest` — idempotent insert test, delete test

---

### Phase 3.2 — Token & Security Repositories

**Implementation Tasks:**

- [x] `EmailVerificationTokenRepository`:
  - `Optional<EmailVerificationToken> findByTokenHashAndConsumedAtIsNullAndInvalidatedAtIsNull(String hash)`
  - `List<EmailVerificationToken> findByUserIdAndConsumedAtIsNullAndInvalidatedAtIsNull(UUID userId)`
  - `int invalidateActiveTokensForUser(UUID userId)` — `@Modifying @Query` update
- [x] `PasswordResetTokenRepository`:
  - `Optional<PasswordResetToken> findByTokenHashAndConsumedAtIsNullAndInvalidatedAtIsNull(String hash)`
  - `int invalidateActiveTokensForUser(UUID userId)` — `@Modifying @Query`
  - `deleteByExpiresAtBeforeAndConsumedAtIsNotNull(Instant cutoff)` — cleanup
- [x] `OauthAccountRepository`:
  - `Optional<OauthAccount> findByProviderIdAndProviderUserIdAndUnlinkedAtIsNull(UUID, String)`
  - `Optional<OauthAccount> findByUserIdAndProviderIdAndUnlinkedAtIsNull(UUID, UUID)`
- [x] `LoginAttemptRepository`:
  - `int countByUserIdAndWasSuccessfulFalseAndAttemptedAtAfter(UUID userId, Instant since)`
  - `int countByIpAddressMaskedAndAttemptedAtAfter(String ip, Instant since)`
- [x] `AccountLockoutRepository`:
  - `Optional<AccountLockout> findByUserIdAndUnlockedAtIsNull(UUID userId)`
- [x] `AuditLogRepository`:
  - `Page<AuditLog> findByTargetUserIdOrderByCreatedAtDesc(UUID, Pageable)`
  - `Page<AuditLog> findByEventTypeIdAndCreatedAtBetween(UUID, Instant, Instant, Pageable)`
  - `Page<AuditLog> findByActorUserIdAndCreatedAtBetween(UUID, Instant, Instant, Pageable)`
- [x] `UserConsentRepository`: `Optional<UserConsent> findTopByUserIdAndRevokedAtIsNullOrderByAcceptedAtDesc(UUID)`
- [x] Lookup repositories: `AccountStatusRepository`, `AuthOriginRepository`, `AuditEventTypeRepository`, `AuditOutcomeRepository`, `LockoutTypeRepository`, `AuthenticationMethodRepository`, `OauthProviderRepository` — all with `findBySlug(String slug)`

**Acceptance Criteria:**
- [x] Token lookup queries filter by `consumedAt IS NULL AND invalidatedAt IS NULL` — active tokens only
- [x] Modifying queries use `@Transactional` and `@Modifying`
- [x] All lookup repositories cache slugs via `@Cacheable` or a singleton bean to avoid repeated DB reads

**Automated Tests:**
- [x] `PasswordResetTokenRepositoryTest` — consumed token not returned, invalidated token not returned
- [x] `AuditLogRepositoryTest` — pagination, ordering, user-scoped query
- [x] `LoginAttemptRepositoryTest` — count query returns correct failed attempt count within time window

---

