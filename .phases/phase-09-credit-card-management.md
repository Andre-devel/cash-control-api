## Phase 9 — Credit Card Management

**Objective:** Implement credit card registration, charge recording, invoice lifecycle, payment, limit tracking, and spending analysis.

**Dependencies:** Phase 5 complete.

**Complexity:** High

### Phase 9.1 — Credit Card Service

**Implementation Tasks:**

- [ ] Create `CreditCardRepository.java`, `InvoiceRepository.java`, `InvoiceItemRepository.java`
- [ ] Create `CreditCardService.java` and `CreditCardServiceImpl.java`:
  - `createCard(CreateCardRequest, UUID userId)`:
    - Validates `closingDay` and `dueDay` 1–28
    - Creates card and opens first invoice for the current billing cycle
  - `listCards(UUID userId)`
  - `editCard(UUID id, EditCardRequest, UUID userId)`
  - `archiveCard(UUID id, UUID userId)`
  - `recordCharge(UUID cardId, RecordChargeRequest, UUID userId)`:
    - Assigns charge to correct invoice based on `competenceDate` vs `closingDay`
    - Updates invoice `totalAmount`
    - Updates card `usedLimit`
  - `getInvoice(UUID cardId, String referenceMonth, UUID userId)` — with paginated charges
  - `payInvoice(UUID invoiceId, PayInvoiceRequest, UUID userId)`:
    - Full payment → `PAID`
    - Partial payment → `PARTIAL`; creates `REVOLVING` item on next invoice
    - Creates debit transaction on source account
  - `getLimitUsage(UUID cardId, UUID userId)` — computed in real time
  - `getSpendingByCategory(UUID cardId, DateRange, UUID userId)`
- [ ] Create `InvoiceCycleCalculator.java` — utility determining which invoice a charge belongs to based on `closingDay`

**Acceptance Criteria:**
- [ ] Charge assigned to correct invoice; post-closing charges go to next cycle
- [ ] Partial payment creates revolving item on next invoice atomically
- [ ] `getLimitUsage` reflects real-time state after every charge or payment
- [ ] `closingDay`/`dueDay` validated 1–28; `BusinessRuleException` otherwise

**Automated Tests:**
- [ ] `InvoiceCycleCalculatorTest` — edge cases around month boundaries and closing day
- [ ] `CreditCardServiceIntegrationTest` — full charge → invoice → payment lifecycle
- [ ] `PartialPaymentTest` — asserts revolving item creation and next invoice update

---

### Phase 9.2 — Credit Card Controller

**Implementation Tasks:**

- [ ] Create `CreditCardController.java` — `@RestController @RequestMapping("/api/v1/cards")`:
  - `POST /` → 201
  - `GET /` → 200
  - `PUT /{id}` → 200
  - `POST /{id}/archive` → 200
  - `POST /{id}/charges` → record charge → 201
  - `GET /{id}/invoices/{referenceMonth}` → invoice detail → 200
  - `POST /invoices/{invoiceId}/pay` → pay invoice → 200
  - `GET /{id}/limit` → limit usage → 200
  - `GET /{id}/spending` → spending by category (with `from`, `to` params) → 200

**Automated Tests:**
- [ ] `CreditCardControllerTest` — HTTP validation for all endpoints

---

