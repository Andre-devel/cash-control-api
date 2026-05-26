## Phase 5 — Transaction Management

**Objective:** Implement the full transaction lifecycle: create, edit, delete, status transitions, list with filters, and attachment management.

**Dependencies:** Phase 4 complete.

**Complexity:** High

### Phase 5.1 — Transaction Repository

**Implementation Tasks:**

- [ ] Create `TransactionRepository.java` — `JpaRepository<Transaction, UUID>`:
  - `findByIdAndUserId(UUID id, UUID userId)`
  - `findAllByUserId(UUID userId, Pageable pageable)`
  - `findAllByAccountIdAndUserId(UUID accountId, UUID userId, Pageable pageable)`
  - Custom JPQL query for filtered search (account, type, status, category, date range, amount range, text search)
  - `existsByAccountIdAndUserIdAndStatusNotIn(UUID accountId, UUID userId, List<TransactionStatus> statuses)` — for account deletion guard
  - `sumPaidAmountByAccountIdAndUserId(UUID accountId, UUID userId)` — for balance computation

---

### Phase 5.2 — Transaction Service

**Implementation Tasks:**

- [ ] Create `TransactionService.java` and `TransactionServiceImpl.java`:
  - `createTransaction(CreateTransactionRequest, UUID userId)` → 201
  - `editTransaction(UUID id, EditTransactionRequest, UUID userId)` — detaches from installment series if part of one
  - `deleteTransaction(UUID id, UUID userId)` — rejects individual deletion of a transfer leg with 422
  - `markAsPaid(UUID id, MarkAsPaidRequest, UUID userId)` — validates `PENDING`/`OVERDUE` → `PAID` transition
  - `cancelTransaction(UUID id, UUID userId)` — sets `cancelledAt`; validates not already cancelled
  - `listTransactions(TransactionFilterRequest, UUID userId, Pageable pageable)` — filtered + paginated
  - `getTransaction(UUID id, UUID userId)` — full detail
  - `detectOverdue(UUID userId)` — transitions eligible `PENDING` → `OVERDUE` (for scheduled/on-demand use)
- [ ] Enforce status transition rules; throw `BusinessRuleException` on invalid transitions
- [ ] Ensure `BigDecimal` arithmetic throughout; never `double` or `float`
- [ ] Apply category rules at creation time

**Acceptance Criteria:**
- [ ] Invalid status transitions rejected with 422
- [ ] `CANCELLED` transactions never affect balance
- [ ] All monetary arithmetic uses `BigDecimal` with `HALF_UP` rounding
- [ ] Category auto-assignment applied at creation when a matching rule exists

**Automated Tests:**
- [ ] `TransactionServiceTest` — unit tests for all methods
- [ ] `TransactionStatusTransitionTest` — asserts valid and invalid transitions
- [ ] `BalanceConsistencyTest` — known transaction sequences verified against expected balance

---

### Phase 5.3 — Transaction Controller

**Implementation Tasks:**

- [ ] Create `TransactionController.java` — `@RestController @RequestMapping("/api/v1/transactions")`:
  - `POST /` → 201
  - `GET /` → paginated list with filter query params → 200
  - `GET /{id}` → full detail → 200
  - `PUT /{id}` → edit → 200
  - `DELETE /{id}` → 204
  - `POST /{id}/pay` → mark as paid → 200
  - `POST /{id}/cancel` → cancel → 200
- [ ] Create request DTOs with Jakarta Validation: `CreateTransactionRequest`, `EditTransactionRequest`, `MarkAsPaidRequest`, `TransactionFilterRequest`
- [ ] Create response DTOs: `TransactionSummaryResponse`, `TransactionDetailResponse`

**Acceptance Criteria:**
- [ ] Validation failures return 400 with field-level error body
- [ ] Entities never leak outside the service boundary
- [ ] `CANCELLED` transactions excluded from list by default; `includeCancelled=true` param to include

**Automated Tests:**
- [ ] `TransactionControllerTest` — `@WebMvcTest` for all endpoints

---

### Phase 5.4 — Attachment Management

**Implementation Tasks:**

- [ ] Create `AttachmentRepository.java`
- [ ] Create `AttachmentService.java`:
  - `attach(UUID transactionId, MultipartFile[] files, UUID userId)` — validates file type, size, and per-transaction limit
  - `deleteAttachment(UUID attachmentId, UUID userId)`
  - `getAttachments(UUID transactionId, UUID userId)`
  - Storage: persist file to configurable storage (local filesystem for dev, S3-compatible for prod) using a `StoragePort` interface
  - Never expose raw file paths in API responses — return signed access references only
- [ ] Create `StoragePort.java` interface + `LocalFileStorageAdapter.java` implementation (dev/test)
- [ ] Add attachment endpoints to `TransactionController`:
  - `POST /{id}/attachments` → 201
  - `GET /{id}/attachments` → 200
  - `DELETE /{id}/attachments/{attachmentId}` → 204

**Acceptance Criteria:**
- [ ] Only PDF, PNG, JPG, JPEG accepted; others → 400
- [ ] File size above configured max → 422
- [ ] Per-transaction limit enforced → 422
- [ ] Storage keys never exposed in API responses

**Automated Tests:**
- [ ] `AttachmentServiceTest` — file validation, limit enforcement

---

