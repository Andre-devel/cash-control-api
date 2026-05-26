## Phase 6 — Installment Transactions

**Objective:** Implement installment series creation, series-wide and individual editing, early settlement, and advance payment.

**Dependencies:** Phase 5 complete.

**Complexity:** High

### Phase 6.1 — Installment Service

**Implementation Tasks:**

- [ ] Create `InstallmentRepository.java` and `InstallmentSeriesRepository.java`
- [ ] Create `InstallmentService.java` and `InstallmentServiceImpl.java`:
  - `createInstallmentSeries(CreateInstallmentRequest, UUID userId)`:
    - Creates `InstallmentSeries` master record
    - Generates individual `Transaction` records for each installment
    - Amount split: `totalAmount / totalInstallments` with remainder on the last installment
    - First installment: `PAID` if firstPaymentDate ≤ today; remainder `PENDING`
    - Monthly `paymentDate` progression from `firstPaymentDate`
  - `editSeries(UUID seriesId, EditSeriesRequest, UUID userId)` — updates description, notes, category, account on all `PENDING`/`OVERDUE` installments
  - `editInstallment(UUID transactionId, EditInstallmentRequest, UUID userId)` — marks `detached = true` on the transaction
  - `earlySettlement(UUID seriesId, EarlySettlementRequest, UUID userId)`:
    - Cancels all remaining `PENDING`/`OVERDUE` installments
    - Creates one `PAID` settlement transaction linked to the series
    - Sets `series.settled = true`, `series.settledAt = now()`
  - `advanceInstallments(AdvanceInstallmentRequest, UUID userId)` — moves payment dates, optionally adjusts amounts

**Acceptance Criteria:**
- [ ] Installment amounts sum to `totalAmount` exactly (no floating-point rounding drift)
- [ ] Remainder handling deterministic (last installment)
- [ ] Detached installments excluded from series-wide edit
- [ ] Early settlement atomic: all cancellations + settlement creation in one transaction

**Automated Tests:**
- [ ] `InstallmentAmountSplitTest` — verifies exact sum and remainder assignment across multiple total/count combinations
- [ ] `EarlySettlementIntegrationTest` — asserts cancellations and settlement creation atomicity

---

### Phase 6.2 — Installment Controller

**Implementation Tasks:**

- [ ] Create `InstallmentController.java` — `@RestController @RequestMapping("/api/v1/installments")`:
  - `POST /` → create series → 201
  - `PUT /series/{seriesId}` → edit series → 200
  - `PUT /{transactionId}` → edit individual installment → 200
  - `POST /series/{seriesId}/settle` → early settlement → 200
  - `POST /advance` → advance installments → 200

**Automated Tests:**
- [ ] `InstallmentControllerTest` — HTTP status and response body validation

---

