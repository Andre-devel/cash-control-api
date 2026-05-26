## Phase 4 — Account & Wallet Management

**Objective:** Implement full CRUD and lifecycle management for user accounts, including balance computation and transfer logic.

**Dependencies:** Phase 3 complete.

**Complexity:** Medium

### Phase 4.1 — Account Repository & Service

**Implementation Tasks:**

- [ ] Create `AccountRepository.java` — extends `JpaRepository<Account, UUID>`:
  - `findAllByUserIdAndDeletedAtIsNull(UUID userId)`
  - `findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId)`
  - `existsByUserIdAndNameAndDeletedAtIsNull(UUID userId, String name)`
- [ ] Create `AccountService.java` interface and `AccountServiceImpl.java`:
  - `createAccount(CreateAccountRequest, UUID userId)` — creates account + seed `MANUAL_ADJUSTMENT` transaction for initial balance
  - `listAccounts(UUID userId, boolean includeArchived)` — sorted by `displayOrder` then `createdAt`
  - `getAccount(UUID id, UUID userId)` — throws `ResourceNotFoundException` if not found
  - `editAccount(UUID id, EditAccountRequest, UUID userId)` — validates name uniqueness
  - `archiveAccount(UUID id, UUID userId)` — sets `archivedAt`; rejects if already archived
  - `unarchiveAccount(UUID id, UUID userId)`
  - `deleteAccount(UUID id, UUID userId)` — only allowed if no transactions beyond the seed record; else throws `BusinessRuleException`
  - `computeBalance(UUID accountId, UUID userId)` — sum of `PAID` transaction amounts with direction encoding
  - `manualAdjustment(ManualAdjustmentRequest, UUID userId)` — creates a `MANUAL_ADJUSTMENT` transaction for the delta

**Acceptance Criteria:**
- [ ] Account name uniqueness per user enforced; `ConflictException` on duplicate
- [ ] Archived account balance excluded from portfolio aggregations
- [ ] Deletion rejected with 422 if account has non-seed transactions

**Automated Tests:**
- [ ] `AccountServiceTest` — unit tests for each service method with mocked repositories
- [ ] `AccountIntegrationTest` — full lifecycle against Testcontainers PostgreSQL

---

### Phase 4.2 — Account Controller

**Implementation Tasks:**

- [ ] Create `AccountController.java` — `@RestController @RequestMapping("/api/v1/accounts")`:
  - `POST /` → `createAccount` → 201
  - `GET /` → `listAccounts` (query param `includeArchived`) → 200
  - `GET /{id}` → `getAccount` → 200
  - `PUT /{id}` → `editAccount` → 200
  - `POST /{id}/archive` → `archiveAccount` → 200
  - `POST /{id}/unarchive` → `unarchiveAccount` → 200
  - `DELETE /{id}` → `deleteAccount` → 204
  - `POST /{id}/adjust` → `manualAdjustment` → 200
- [ ] Create request DTOs: `CreateAccountRequest`, `EditAccountRequest`, `ManualAdjustmentRequest`
- [ ] Create response DTO: `AccountResponse` (never expose the JPA entity)

**Acceptance Criteria:**
- [ ] All endpoints require a valid JWT
- [ ] `userId` always sourced from the JWT; never from the request body
- [ ] `AccountResponse` includes computed balance but never the JPA entity

**Automated Tests:**
- [ ] `AccountControllerTest` — `@WebMvcTest` for all endpoints; validates HTTP status and response body structure

---

### Phase 4.3 — Transfer Between Accounts

**Implementation Tasks:**

- [ ] Add `createTransfer(TransferRequest, UUID userId)` to `AccountService`:
  - Validates both accounts belong to `userId`
  - Validates source ≠ destination
  - Validates neither account is archived
  - Creates two linked `TRANSFER` transactions atomically with the same `transferGroupId`
- [ ] Add `POST /api/v1/accounts/transfers` endpoint to `AccountController`
- [ ] Add `DELETE /api/v1/accounts/transfers/{groupId}` — deletes both legs atomically
- [ ] Create request DTO: `TransferRequest`

**Acceptance Criteria:**
- [ ] Both legs created atomically; if either fails, neither is persisted
- [ ] Transfer nets to zero in portfolio balance calculations
- [ ] Deleting one leg individually rejected with 422

**Automated Tests:**
- [ ] `TransferIntegrationTest` — asserts both legs are created; asserts portfolio balance is unchanged

---

