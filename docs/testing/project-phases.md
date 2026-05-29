# Integration Tests Roadmap — cash-control-api (v1)

**Stack:** Java 25 · Spring Boot 4.0.6 · JUnit 5 · Testcontainers · PostgreSQL 18 · MockMvc  
**Scope:** Repository-level · Service-level · API end-to-end · Cross-domain flows  
**Generated:** 2026-05-29  
**Status legend:** `[x]` = implemented · `[ ]` = pending

---

## Why This Roadmap Exists

The existing test suite has strong unit coverage (mocked repositories via Mockito) and good schema-level tests (migrations, constraints, indexes). However, a class of bugs is invisible to mocked tests: **JPQL and native queries that only fail when executed against real PostgreSQL**.

The concrete incident that motivated this roadmap: `TransactionRepository.findWithFilters` uses `LOWER(CONCAT('%', :searchText, '%'))` in JPQL. Hibernate 6 translates this to `lower(('%'||?||'%'))`. PostgreSQL infers the untyped `?` parameter as `bytea` in certain driver/Hibernate version combinations, causing `ERROR: function lower(bytea) does not exist`. This bug was undetectable by `TransactionServiceTest` because the repository was mocked — the query never ran against PostgreSQL.

**The fix for this class of bugs is repository integration tests: tests that call real JPA repository methods against a real Testcontainers PostgreSQL instance and assert correct query results.**

---

## Test Infrastructure (Already in Place)

| Component | Class | Notes |
|---|---|---|
| Testcontainers config | `PostgresTestContainerConfig` | Shared container, reused across tests |
| Base integration class | `BaseIntegrationTest` | `@SpringBootTest(RANDOM_PORT)`, MockMvc wired |
| Base repository class | `BaseRepositoryTest` | `@SpringBootTest`, no HTTP stack |
| Test entity factory | `TestEntityFactory` | Shared helpers for creating domain objects |
| Test profile config | `application-test.yml` | Overrides datasource to Testcontainers |

All new tests in this roadmap must extend `BaseRepositoryTest` (repository-level) or `BaseIntegrationTest` (service/API-level). No new Testcontainers wiring is needed.

---

## Implementation Strategy

Phases are ordered from the bottom of the stack upward:

1. **IT-1 — Repository Integration Tests**: validate every JPQL and native query executes correctly against PostgreSQL, with all parameter combinations.
2. **IT-2 — Service Integration Tests**: validate service business logic end-to-end with a real database, without mocking the repository layer.
3. **IT-3 — API End-to-End Tests**: validate HTTP request → controller → service → repository → database flows, catching contract bugs that unit tests miss.
4. **IT-4 — Cross-Domain Flow Tests**: validate multi-domain scenarios (e.g., transaction creation affecting account balance, dashboard aggregating across multiple features).

Each phase is independent; IT-2 does not require IT-1 to be complete first, but IT-1 tests are cheaper (no HTTP stack) and should be written first.

---

## Phase IT-1 — Repository Integration Tests

**Objective:** Execute every JPQL and native query in every repository against a real PostgreSQL container. Assert correct results for all parameter combinations, including NULL parameters, edge values, and text search.

**The rule:** If a repository method has a custom `@Query`, it needs an integration test that exercises the query with a non-null value for every parameter.

**Dependencies:** `BaseRepositoryTest` infrastructure in place.

**Complexity:** Medium

---

### IT-1.1 — TransactionRepository Integration Tests

**File:** `TransactionRepositoryIntegrationTest.java`

**Why this is the highest priority:** The `lower(bytea)` production bug came from `findWithFilters`. This test would have caught it before merge.

**Implementation Tasks:**

- [x] Create `TransactionRepositoryIntegrationTest` extending `BaseRepositoryTest`
- [x] Seed helper: `seedTransaction(UUID userId, String description, TransactionStatus, LocalDate)` — creates account + transaction, returns the saved entity
- [x] Test `findWithFilters` — `searchText` on `description`:
  - Seed two transactions: `"Supermercado Pão de Açúcar"` and `"Farmácia CVS"`
  - Call `findWithFilters(..., searchText = "supermercado", ...)` (lowercase search against mixed-case data)
  - Assert exactly one result, matching the supermarket transaction
  - **This is the regression test for the `lower(bytea)` incident**
- [x] Test `findWithFilters` — `searchText` on `notes`:
  - Seed a transaction with `notes = "Compra parcelada"`
  - Call `findWithFilters(..., searchText = "parcelada", ...)` with a non-matching description
  - Assert the notes field is also searched
- [x] Test `findWithFilters` — all filters null (returns all user transactions):
  - Seed 3 transactions for user A, 2 for user B
  - Assert user A gets exactly 3 results; user B gets exactly 2 (user scoping)
- [x] Test `findWithFilters` — `accountId` filter:
  - Seed transactions on two different accounts
  - Assert filtering by `accountId` returns only the correct account's transactions
- [x] Test `findWithFilters` — `type` filter (`INCOME`, `EXPENSE`):
  - Seed one of each type; assert each filter returns exactly one result
- [x] Test `findWithFilters` — `status` filter (`PAID`, `PENDING`, `CANCELLED`):
  - Seed one of each status; assert each filter returns exactly one result
- [x] Test `findWithFilters` — `competenceDate` range:
  - Seed transactions on three different dates; assert date range returns only the middle date
- [x] Test `findWithFilters` — `paymentDate` range:
  - Same as above for payment date
- [x] Test `findWithFilters` — `amountMin`/`amountMax` range:
  - Seed transactions at 100, 500, 1000; assert range 200–600 returns only 500
- [x] Test `findWithFilters` — `includeCancelled = false` (default):
  - Seed one PAID and one CANCELLED transaction
  - Assert only the PAID one is returned when `includeCancelled = false`
  - Assert both are returned when `includeCancelled = true`
- [x] Test `findWithFilters` — combined filters (accountId + type + searchText):
  - Assert combining multiple filters narrows results correctly
- [x] Test `sumPaidAmountByAccountIdAndUserId`:
  - Seed INCOME = 1000, EXPENSE = 300 (both PAID), PENDING = 500
  - Assert sum = 700 (only PAID; PENDING excluded)
- [x] Test `sumTotalBalanceExcludingType` — excludes INVESTMENT account type:
  - Seed CHECKING account with 1000 PAID, INVESTMENT account with 5000 PAID
  - Assert sum excludes the investment balance
- [x] Test `findUpcomingBills`:
  - Seed PENDING transactions at today+1, today+3, today+10
  - Call with `deadline = today+7`
  - Assert only the two within-window transactions are returned
- [x] Test `findLargestExpenses`:
  - Seed 5 PAID EXPENSE transactions with distinct amounts
  - Call with `limit = 3`
  - Assert top 3 by amount are returned in descending order
- [x] Test `findTopCategoriesByDescriptionText`:
  - Seed 3 transactions with description `"Mercado"` linked to category A
  - Seed 1 transaction with description `"Mercado Extra"` linked to category B
  - Assert category A ranks first
- [x] Test `markOverdueForUser`:
  - Seed PENDING with `paymentDate` yesterday, and PENDING with `paymentDate` tomorrow
  - Call `markOverdueForUser` with today's date
  - Assert only the overdue one was transitioned; tomorrow's stays PENDING

**Acceptance Criteria:**
- [x] All tests pass against a real PostgreSQL 18 container
- [x] `findWithFilters` with `searchText` is covered by at least one test — the regression guard for the `lower(bytea)` bug
- [x] Every `@Query`-annotated method in `TransactionRepository` has at least one test exercising its non-null parameters

**Automated Tests:**
- [x] `TransactionRepositoryIntegrationTest` — 17 test cases

---

### IT-1.2 — AccountRepository Integration Tests

**File:** `AccountRepositoryIntegrationTest.java`

**Implementation Tasks:**

- [x] Create `AccountRepositoryIntegrationTest` extending `BaseRepositoryTest`
- [x] Test `findAllByUserIdAndDeletedAtIsNull`:
  - Seed 2 active + 1 soft-deleted account for user A
  - Assert only 2 returned
- [x] Test `findByIdAndUserIdAndDeletedAtIsNull` — cross-user isolation:
  - Seed account for user A; query with user B's ID
  - Assert empty result
- [x] Test `existsByUserIdAndNameAndDeletedAtIsNull` — name uniqueness:
  - Seed account `"Nubank"` for user A; assert `exists = true`
  - Assert user B returns `false` for the same name (user scoping)
- [x] Test `existsByAccount_IdAndUserIdAndStatusNotIn` — deletion guard:
  - Seed account with one PAID and one CANCELLED transaction
  - Assert `exists = true` when querying `statusNotIn = [CANCELLED]` (has PAID)
  - Seed account with only CANCELLED transaction
  - Assert `exists = false`

**Acceptance Criteria:**
- [x] All tests pass; cross-user isolation verified at repository level

**Automated Tests:**
- [x] `AccountRepositoryIntegrationTest` — 5 test cases

---

### IT-1.3 — CategoryRepository Integration Tests

**File:** `CategoryRepositoryIntegrationTest.java`

**Implementation Tasks:**

- [x] Create `CategoryRepositoryIntegrationTest` extending `BaseRepositoryTest`
- [x] Test `findAllSystemCategories` — returns only `user_id IS NULL` rows:
  - Assert count matches the number of rows in `V13__seed_default_categories.sql`
  - Assert none of the returned categories have a non-null `userId`
- [x] Test `findAllByUserId` — returns only user-defined categories:
  - Seed 2 user categories for user A; assert 2 returned and none are system categories
- [x] Test `existsByUserIdAndParentIdAndName` — name uniqueness within parent scope:
  - Seed subcategory `"Aluguel"` under parent `"Moradia"` for user A
  - Assert `exists = true` for the same combination
  - Assert `exists = false` for a different parent or a different user
- [x] Test `findTopCategoriesByDescriptionText` (in `TransactionRepository`):
  - Already covered in IT-1.1 — skip here
- [x] Test category suggestion fallback — `findTopCategoriesByFrequency`:
  - Seed 3 transactions with category A, 1 with category B
  - Assert category A ranks first in frequency result

**Acceptance Criteria:**
- [x] System category seed count verified programmatically

**Automated Tests:**
- [x] `CategoryRepositoryIntegrationTest` — 5 test cases

---

### IT-1.4 — CreditCard & Invoice Repository Integration Tests

**File:** `CreditCardRepositoryIntegrationTest.java`

**Implementation Tasks:**

- [x] Create `CreditCardRepositoryIntegrationTest` extending `BaseRepositoryTest`
- [x] Test `findByIdAndUserId` — cross-user isolation on credit card access
- [x] Test `findByUserIdAndArchivedAtIsNull` — archived cards excluded from active list
- [x] Test `findByReferenceMonthAndCreditCard_Id` — invoice lookup by reference month:
  - Seed two invoices for the same card with different reference months
  - Assert the correct invoice is returned for each month
- [x] Test `findByCreditCard_IdAndStatusIn` — upcoming invoices by status:
  - Seed OPEN, CLOSED, PAID invoices
  - Assert only CLOSED is returned when filtering for `[CLOSED, PARTIAL, OVERDUE]`

**Acceptance Criteria:**
- [x] Invoice lookup by reference month works with real PostgreSQL

**Automated Tests:**
- [x] `CreditCardRepositoryIntegrationTest` — 4 test cases

---

### IT-1.5 — Recurrence & Installment Repository Integration Tests

**File:** `RecurrenceRepositoryIntegrationTest.java`

**Implementation Tasks:**

- [x] Create `RecurrenceRepositoryIntegrationTest` extending `BaseRepositoryTest`
- [x] Test scheduler query — `findAllByStatusAndNextOccurrenceDateLessThanEqual`:
  - Seed ACTIVE rule with `nextOccurrenceDate = today`, ACTIVE rule with tomorrow, PAUSED rule with today
  - Assert only today's ACTIVE rule is returned
- [x] Test `findAllByRecurrenceRule_IdAndStatusIn`:
  - Seed PENDING and PAID transactions linked to the same rule
  - Assert only PENDING is returned when filtering for `[PENDING, OVERDUE]`
- [x] Test `findAllByInstallmentSeries_Id`:
  - Seed 3 installments linked to the same series + 1 unlinked transaction
  - Assert exactly 3 are returned

**Acceptance Criteria:**
- [x] Scheduler query verified against real PostgreSQL date arithmetic

**Automated Tests:**
- [x] `RecurrenceRepositoryIntegrationTest` — 3 test cases

---

### IT-1.6 — Dashboard Native Query Integration Tests

**File:** `DashboardRepositoryIntegrationTest.java`

**Implementation Tasks:**

- [x] Create `DashboardRepositoryIntegrationTest` extending `BaseRepositoryTest`
- [x] Test `findMonthlyIncomeExpense` (native query with `TO_CHAR`, `CAST(:accountId AS uuid)`):
  - Seed INCOME 1000 and EXPENSE 300 for `2026-03`, INCOME 500 for `2026-04`
  - Call with `from = 2026-03-01`, `to = 2026-04-30`, `accountId = null`
  - Assert two rows returned: `2026-03` and `2026-04` with correct amounts
  - Call again with a specific `accountId` — assert filtering applies
  - **This test catches `CAST(:accountId AS uuid)` driver incompatibilities**
- [x] Test `sumNetWorthUpTo`:
  - Seed PAID INCOME and EXPENSE transactions on different dates
  - Assert cumulative sum up to a cutoff date includes only transactions before/on that date
- [x] Test `findCategoryBreakdown`:
  - Seed EXPENSE transactions in two categories
  - Assert grouped result has correct per-category totals and is ordered by amount DESC
- [x] Test `sumTotalNetWorth` — includes all non-archived non-deleted accounts:
  - Seed PAID transaction on active account and on archived account
  - Assert archived account is excluded from net worth

**Acceptance Criteria:**
- [x] Native queries (`nativeQuery = true`) tested against real PostgreSQL
- [x] `CAST(:accountId AS uuid)` with null value verified to return all accounts

**Automated Tests:**
- [x] `DashboardRepositoryIntegrationTest` — 4 test cases (5 implemented)

---

## Phase IT-2 — Service Integration Tests

**Objective:** Test the service layer against a real database. No repository mocks. The HTTP stack is not needed; `BaseRepositoryTest` is sufficient. These tests catch bugs at the service → repository → SQL boundary that unit tests miss.

**Dependencies:** Phase IT-1 (informally — IT-1 catches low-level issues; IT-2 tests the orchestration).

**Complexity:** Medium-High

---

### IT-2.1 — TransactionServiceIntegrationTest

**File:** `TransactionServiceIntegrationTest.java`

**Implementation Tasks:**

- [x] Create `TransactionServiceIntegrationTest` extending `BaseRepositoryTest`
- [x] Inject `TransactionService`, `AccountRepository`, seed user/account helpers
- [x] Test `listTransactions` with `searchText` filter:
  - Create two transactions with distinct descriptions
  - Call `listTransactions` with a search term matching only one
  - Assert one result returned — **this is the service-level regression test for the `lower(bytea)` bug**
- [x] Test `listTransactions` — pagination works correctly:
  - Seed 15 transactions
  - Assert page 0 / size 10 returns 10 items
  - Assert page 1 / size 10 returns 5 items
- [x] Test `listTransactions` — `includeCancelled = false` by default:
  - Seed 3 PAID and 2 CANCELLED transactions
  - Assert 3 returned; with `includeCancelled = true` assert 5 returned
- [x] Test `createTransaction` — category rule auto-applied:
  - Seed a category rule matching `"farmácia"` → category `"Saúde"`
  - Create an EXPENSE with description `"Farmácia Popular"`
  - Load the saved transaction; assert `category = "Saúde"`
- [x] Test `detectOverdue`:
  - Seed PENDING transaction with `paymentDate = yesterday` and `paymentDate = tomorrow`
  - Call `detectOverdue(userId)`
  - Assert yesterday's transaction is now OVERDUE; tomorrow's is still PENDING
- [x] Test `markAsPaid` — full persistence:
  - Create PENDING transaction; call `markAsPaid`; reload from DB
  - Assert status = PAID and paymentDate is set
- [x] Test `cancelTransaction` — full persistence:
  - Create PAID transaction; call `cancelTransaction`; reload from DB
  - Assert status = CANCELLED and `cancelledAt` is not null

**Acceptance Criteria:**
- [x] `listTransactions` with `searchText` passes against real PostgreSQL
- [x] All DB state changes are reloaded from DB (not from in-memory entity cache)

**Automated Tests:**
- [x] `TransactionServiceIntegrationTest` — 7 test cases

---

### IT-2.2 — InstallmentServiceIntegrationTest

**File:** `InstallmentServiceIntegrationTest.java`

**Implementation Tasks:**

- [x] Create `InstallmentServiceIntegrationTest` extending `BaseRepositoryTest`
- [x] Test `createInstallmentSeries` — full persistence:
  - Create series with total = 1200, count = 3
  - Reload all 3 installment transactions from DB
  - Assert amounts are [400, 400, 400] (or [399, 400, 401] per remainder rule)
  - Assert `installmentSeries_id` is set on all three
  - Assert `installment_number` sequence is 1, 2, 3
- [x] Test `createInstallmentSeries` — amount remainder on last installment:
  - Create series with total = 100, count = 3
  - Assert amounts are [33.33, 33.33, 33.34] — remainder on last (scale=2, DOWN rounding)
- [x] Test `earlySettlement` — full atomicity:
  - Create series with 3 installments (2 PENDING, 1 PAID)
  - Call `earlySettlement`
  - Reload from DB: assert the 2 PENDING are now CANCELLED, the PAID is unchanged
  - Assert a new PAID settlement transaction was created
  - Assert `installmentSeries.settled = true`
- [x] Test `editSeries` — detached installment not updated:
  - Create series with 3 installments; mark installment 2 as detached
  - Call `editSeries` with a new category
  - Assert installment 2 (detached) retains its original category
  - Assert installments 1 and 3 have the new category

**Acceptance Criteria:**
- [x] `earlySettlement` atomicity verified by checking all entities after the transaction commits

**Automated Tests:**
- [x] `InstallmentServiceIntegrationTest` — 4 test cases

---

### IT-2.3 — CategoryServiceIntegrationTest

**File:** `CategoryServiceIntegrationTest.java`

**Implementation Tasks:**

- [x] Create `CategoryServiceIntegrationTest` extending `BaseRepositoryTest`
- [x] Test `listCategories` — system + user categories merged:
  - Assert the seeded system categories are present in the result
  - Create 2 user-defined categories; assert they also appear
- [x] Test `createCategory` — name uniqueness enforced per user:
  - Create category `"Pets"` for user A
  - Assert creating `"Pets"` again for user A throws `ConflictException`
  - Assert creating `"Pets"` for user B succeeds (user scoping)
- [x] Test `archiveCategory` — cascades to all subcategories:
  - Create parent category with 3 subcategories
  - Call `archiveCategory` on the parent
  - Reload all from DB; assert parent and all 3 subs have `archivedAt` set
- [x] Test `archiveCategory` — system category rejected:
  - Attempt to archive a system category (seeded by Flyway)
  - Assert `ResourceNotFoundException` is thrown (system categories have user_id=null, unreachable via findByIdAndUserId)
- [x] Test `suggestCategory` — frequency-based result:
  - Seed 5 transactions with category A and description `"mercado"`
  - Seed 1 transaction with category B and description `"mercado extra"`
  - Call `suggestCategory("mercado", userId)`
  - Assert category A is the top suggestion

**Acceptance Criteria:**
- [x] `archiveCategory` cascade verified by reloading each subcategory individually from DB

**Automated Tests:**
- [x] `CategoryServiceIntegrationTest` — 5 test cases

---

### IT-2.4 — DashboardServiceIntegrationTest

**File:** `DashboardServiceIntegrationTest.java`  
*(complements the existing `OverviewMetricsIntegrationTest` — this one covers chart endpoints)*

**Implementation Tasks:**

- [x] Create `DashboardServiceIntegrationTest` extending `BaseRepositoryTest`
- [x] Test `getCategoryPieChart`:
  - Seed 3 EXPENSE transactions: 500 in "Moradia", 300 in "Alimentação", 200 in "Saúde"
  - Assert 3 slices; percentages sum to 100; "Moradia" is the largest slice
- [x] Test `getMonthlyBarChart` — months with no transactions filled with zeroes:
  - Seed transactions only in Jan and Mar (skip Feb)
  - Call with range Jan–Mar
  - Assert 3 month entries; Feb has `income = 0` and `expenses = 0`
- [x] Test `getNetWorthEvolution`:
  - Seed INCOME 1000 on 2026-01-01, EXPENSE 200 on 2026-02-01
  - Assert net worth at 2026-01-01 = 1000; at 2026-02-28 = 800
- [x] Test `getLargestExpenses`:
  - Seed 5 PAID EXPENSE transactions with distinct amounts
  - Assert top 3 returned, ordered by amount descending
- [x] Test `getUpcomingBills`:
  - Seed PENDING transactions at today+1, today+3, today+8
  - Call with `daysAhead = 7`
  - Assert only 2 bills returned

**Acceptance Criteria:**
- [x] Monthly bar chart zero-fill verified with a month gap in seed data
- [x] Net worth evolution snapshot dates produce correct cumulative sums

**Automated Tests:**
- [x] `DashboardServiceIntegrationTest` — 5 test cases

---

## Phase IT-3 — API End-to-End Integration Tests

**Objective:** Test the full HTTP stack: JWT auth → controller → service → repository → PostgreSQL. These tests catch contract bugs (wrong HTTP status, wrong response body shape, missing validation, auth bypass) that cannot be caught by mocked controller tests.

**Approach:** Extend `BaseIntegrationTest` (full `@SpringBootTest` with MockMvc). No mocks for service or repository. All state must be set up via API calls or direct DB seeding.

**Dependencies:** Service layer works correctly (verified by IT-2).

**Complexity:** High

---

### IT-3.1 — Transaction API End-to-End Tests

**File:** `TransactionApiIntegrationTest.java`

**Implementation Tasks:**

- [ ] Create `TransactionApiIntegrationTest` extending `BaseIntegrationTest`
- [ ] Set up: seed a user + JWT via `AuthIntegrationTest` pattern; seed an account
- [ ] Test `POST /api/v1/transactions` — create INCOME, assert 201 and response body
- [ ] Test `GET /api/v1/transactions` — list with no filters, assert 200 and correct total
- [ ] Test `GET /api/v1/transactions?searchText=supermercado` — assert text search works end-to-end (HTTP → PostgreSQL):
  - Create two transactions with distinct descriptions
  - GET with `searchText` matching one
  - Assert `content` has exactly 1 item with the expected description
  - **This is the full-stack regression test for the `lower(bytea)` bug**
- [ ] Test `GET /api/v1/transactions?type=INCOME` — type filter
- [ ] Test `GET /api/v1/transactions?competenceDateFrom=2026-01-01&competenceDateTo=2026-01-31` — date range filter
- [ ] Test `GET /api/v1/transactions/{id}` — get detail
- [ ] Test `PUT /api/v1/transactions/{id}` — edit
- [ ] Test `POST /api/v1/transactions/{id}/pay` — mark as paid, assert status transitions
- [ ] Test `POST /api/v1/transactions/{id}/cancel` — cancel, assert response
- [ ] Test `DELETE /api/v1/transactions/{id}` — assert 204
- [ ] Test `GET /api/v1/transactions` with invalid JWT — assert 401
- [ ] Test `GET /api/v1/transactions/{id}` for another user's transaction — assert 404 (user scoping, not 403)

**Acceptance Criteria:**
- [ ] `searchText` filter works through the full HTTP → DB stack
- [ ] Cross-user access returns 404, not 403 (anti-enumeration)
- [ ] All requests without JWT return 401

**Automated Tests:**
- [ ] `TransactionApiIntegrationTest` — 12 test cases

---

### IT-3.2 — Account API End-to-End Tests

**File:** `AccountApiIntegrationTest.java`  
*(complements the existing `AccountIntegrationTest` — this one focuses on full HTTP flow)*

**Implementation Tasks:**

- [ ] Create `AccountApiIntegrationTest` extending `BaseIntegrationTest`
- [ ] Test `POST /api/v1/accounts` — create, assert 201 and `id` in response
- [ ] Test `POST /api/v1/accounts` — duplicate name, assert 409
- [ ] Test `GET /api/v1/accounts` — list excludes archived by default
- [ ] Test `GET /api/v1/accounts?includeArchived=true` — archived accounts included
- [ ] Test `POST /api/v1/accounts/{id}/archive` → `POST /api/v1/accounts/{id}/unarchive` cycle
- [ ] Test `DELETE /api/v1/accounts/{id}` — account with non-seed transactions, assert 422
- [ ] Test `POST /api/v1/accounts/{id}/adjust` — manual balance adjustment, assert balance changes
- [ ] Test `GET /api/v1/accounts/{id}` for another user's account — assert 404

**Acceptance Criteria:**
- [ ] Balance in `GET /api/v1/accounts/{id}` reflects real computed sum from DB

**Automated Tests:**
- [ ] `AccountApiIntegrationTest` — 8 test cases

---

### IT-3.3 — Installment API End-to-End Tests

**File:** `InstallmentApiIntegrationTest.java`

**Implementation Tasks:**

- [ ] Create `InstallmentApiIntegrationTest` extending `BaseIntegrationTest`
- [ ] Test `POST /api/v1/installments` — create series, assert 201, assert N transactions created
- [ ] Test `GET /api/v1/transactions?installmentSeriesId={id}` — verify series transactions are listable
- [ ] Test `PUT /api/v1/installments/series/{id}` — series edit, assert PENDING installments updated
- [ ] Test `PUT /api/v1/installments/{transactionId}` — individual edit, assert detach flag set
- [ ] Test `POST /api/v1/installments/series/{id}/settle` — early settlement, assert CANCELLED + settlement transaction created

**Acceptance Criteria:**
- [ ] Series creation creates exactly `totalInstallments` transactions in DB

**Automated Tests:**
- [ ] `InstallmentApiIntegrationTest` — 5 test cases

---

### IT-3.4 — Recurrence API End-to-End Tests

**File:** `RecurrenceApiIntegrationTest.java`

**Implementation Tasks:**

- [ ] Create `RecurrenceApiIntegrationTest` extending `BaseIntegrationTest`
- [ ] Test `POST /api/v1/recurrences` — create, assert first instance generated in `transactions`
- [ ] Test `POST /api/v1/recurrences/{id}/pause` — assert status = PAUSED
- [ ] Test `POST /api/v1/recurrences/{id}/resume` — assert status = ACTIVE
- [ ] Test `DELETE /api/v1/recurrences/{id}?strategy=FUTURE_ONLY` — assert PENDING cancelled, PAID untouched
- [ ] Test `DELETE /api/v1/recurrences/{id}?strategy=ALL` — assert all PENDING cancelled

**Acceptance Criteria:**
- [ ] First transaction instance created synchronously on recurrence creation

**Automated Tests:**
- [ ] `RecurrenceApiIntegrationTest` — 5 test cases

---

### IT-3.5 — Category API End-to-End Tests

**File:** `CategoryApiIntegrationTest.java`

**Implementation Tasks:**

- [ ] Create `CategoryApiIntegrationTest` extending `BaseIntegrationTest`
- [ ] Test `GET /api/v1/categories` — system categories present in response
- [ ] Test `POST /api/v1/categories` — create user category, assert 201
- [ ] Test `POST /api/v1/categories` — third-level nesting rejected, assert 422
- [ ] Test `POST /api/v1/categories/{id}/archive` — assert cascade to subcategories via follow-up GET
- [ ] Test `GET /api/v1/categories/suggest?description=mercado` — assert non-empty suggestion list
- [ ] Test `POST /api/v1/categories/rules` + `GET /api/v1/categories/rules` — rule lifecycle
- [ ] Test `DELETE /api/v1/categories/rules/{id}` — assert 204 and rule removed from list

**Acceptance Criteria:**
- [ ] System categories always present regardless of user

**Automated Tests:**
- [ ] `CategoryApiIntegrationTest` — 7 test cases

---

### IT-3.6 — Credit Card API End-to-End Tests

**File:** `CreditCardApiIntegrationTest.java`  
*(complements `CreditCardServiceIntegrationTest`)*

**Implementation Tasks:**

- [ ] Create `CreditCardApiIntegrationTest` extending `BaseIntegrationTest`
- [ ] Test `POST /api/v1/cards` — create card, assert first invoice opened
- [ ] Test `POST /api/v1/cards/{id}/charges` — record charge, assert invoice total updated
- [ ] Test `GET /api/v1/cards/{id}/invoices/{referenceMonth}` — assert invoice detail returned
- [ ] Test `POST /api/v1/invoices/{id}/pay` — full payment, assert invoice status = PAID and debit transaction created on source account
- [ ] Test `GET /api/v1/cards/{id}/limit` — assert `usedLimit` reflects charges
- [ ] Test `POST /api/v1/cards/{id}/charges` — charge exceeding limit, assert 422

**Acceptance Criteria:**
- [ ] Invoice total updated in real time after each charge (not from cache)
- [ ] Full payment creates a debit transaction linked to the source account

**Automated Tests:**
- [ ] `CreditCardApiIntegrationTest` — 6 test cases

---

### IT-3.7 — Dashboard API End-to-End Tests

**File:** `DashboardApiIntegrationTest.java`

**Implementation Tasks:**

- [ ] Create `DashboardApiIntegrationTest` extending `BaseIntegrationTest`
- [ ] Test `GET /api/v1/dashboard/overview` — assert all fields present and correct types
- [ ] Test `GET /api/v1/dashboard/charts/categories?type=EXPENSE` — assert non-empty slices after seeding
- [ ] Test `GET /api/v1/dashboard/charts/monthly?months=3` — assert 3 month entries returned
- [ ] Test `GET /api/v1/dashboard/charts/net-worth` — assert response has date-keyed entries
- [ ] Test `GET /api/v1/dashboard/widgets/upcoming-bills` — assert only within-window bills returned
- [ ] Test `GET /api/v1/dashboard/widgets/largest-expenses` — assert sorted by amount desc
- [ ] Test `GET /api/v1/dashboard/widgets/recent-transactions` — assert ordered by competence date desc

**Acceptance Criteria:**
- [ ] Overview endpoint returns correct `monthlyIncome`, `monthlyExpenses`, `savings` after seeded transactions
- [ ] All responses return 200 even when the user has no transactions (empty but valid response)

**Automated Tests:**
- [ ] `DashboardApiIntegrationTest` — 7 test cases

---

## Phase IT-4 — Cross-Domain Flow Tests

**Objective:** Validate scenarios that span multiple domains and would only fail if the entire stack is integrated. These are the most realistic tests — they simulate what a user actually does.

**Dependencies:** IT-2 and IT-3 passing.

**Complexity:** High

---

### IT-4.1 — Account Balance Consistency Flow

**File:** `AccountBalanceFlowTest.java`

**Implementation Tasks:**

- [ ] Create `AccountBalanceFlowTest` extending `BaseIntegrationTest`
- [ ] Flow: create account → create INCOME → create EXPENSE → cancel EXPENSE → mark pending INCOME as paid → check balance
  - Assert balance = (INCOME sum) after EXPENSE cancelled
  - Assert cancelled transaction not in balance calculation
- [ ] Flow: create installment series → assert sum of installment amounts matches balance contribution after paying each
- [ ] Flow: create transfer A→B → assert A balance decreases, B balance increases, portfolio total unchanged

**Acceptance Criteria:**
- [ ] Balance computed from DB at each step (cache cleared between assertions)

**Automated Tests:**
- [ ] `AccountBalanceFlowTest` — 3 flow tests

---

### IT-4.2 — Transaction Search & Filter Flow

**File:** `TransactionSearchFlowTest.java`

**This is the direct regression suite for the `lower(bytea)` bug.**

**Implementation Tasks:**

- [ ] Create `TransactionSearchFlowTest` extending `BaseIntegrationTest`
- [ ] Flow: create 20 transactions with varied descriptions, notes, types, statuses, dates, amounts → run all combinations of `GET /api/v1/transactions` with different filter parameters → assert correct counts and correct result sets at each step
- [ ] Specifically assert: `searchText` with unicode characters (`"Pão"`, `"ação"`, `"café"`) works correctly (PostgreSQL `lower()` with non-ASCII)
- [ ] Assert: `searchText` combined with `type` + `status` + `dateRange` returns the single correct transaction
- [ ] Assert: `searchText` with empty string returns same result as no `searchText` filter

**Acceptance Criteria:**
- [ ] Unicode `lower()` works correctly in PostgreSQL for Brazilian Portuguese characters

**Automated Tests:**
- [ ] `TransactionSearchFlowTest` — 3 flow tests (20 transactions each)

---

### IT-4.3 — Credit Card Full Lifecycle Flow

**File:** `CreditCardLifecycleFlowTest.java`

**Implementation Tasks:**

- [ ] Create `CreditCardLifecycleFlowTest` extending `BaseIntegrationTest`
- [ ] Flow: create card → record 5 charges (some pre-closing, some post-closing) → assert invoice assignment correct → close invoice → pay invoice partially → assert revolving item on next invoice → pay revolving → assert final invoice status = PAID
- [ ] Assert: account balance decreases by full invoice payment amount

**Acceptance Criteria:**
- [ ] Invoice cycle calculator and revolving item creation verified end-to-end

**Automated Tests:**
- [ ] `CreditCardLifecycleFlowTest` — 1 flow test

---

### IT-4.4 — Overdue Detection & Recurrence Generation Flow

**File:** `ScheduledJobsFlowTest.java`

**Implementation Tasks:**

- [ ] Create `ScheduledJobsFlowTest` extending `BaseIntegrationTest`
- [ ] Flow: create PENDING transactions with various payment dates → invoke `OverdueDetectionScheduler` directly (not via cron) → assert correct transitions
- [ ] Flow: create recurrence rule with `nextOccurrenceDate = today` → invoke `RecurrenceGenerationScheduler` directly → assert new transaction instance created → invoke again → assert no duplicate created (idempotency)
- [ ] Flow: trigger scheduler twice in a row → assert no duplicate overdue transitions

**Acceptance Criteria:**
- [ ] Scheduler idempotency verified: running twice produces the same final state as running once

**Automated Tests:**
- [ ] `ScheduledJobsFlowTest` — 3 flow tests

---

## Summary

| Phase | Files to Create | Test Cases | Priority |
|---|---|---|---|
| IT-1.1 | `TransactionRepositoryIntegrationTest` | 17 | **Critical** — regression for `lower(bytea)` |
| IT-1.2 | `AccountRepositoryIntegrationTest` | 5 | High |
| IT-1.3 | `CategoryRepositoryIntegrationTest` | 5 | High |
| IT-1.4 | `CreditCardRepositoryIntegrationTest` | 4 | High |
| IT-1.5 | `RecurrenceRepositoryIntegrationTest` | 3 | Medium |
| IT-1.6 | `DashboardRepositoryIntegrationTest` | 4 | High |
| IT-2.1 | `TransactionServiceIntegrationTest` | 7 | **Critical** — service-level regression |
| IT-2.2 | `InstallmentServiceIntegrationTest` | 4 | Medium |
| IT-2.3 | `CategoryServiceIntegrationTest` | 5 | Medium |
| IT-2.4 | `DashboardServiceIntegrationTest` | 5 | Medium |
| IT-3.1 | `TransactionApiIntegrationTest` | 12 | **Critical** — full-stack regression |
| IT-3.2 | `AccountApiIntegrationTest` | 8 | High |
| IT-3.3 | `InstallmentApiIntegrationTest` | 5 | Medium |
| IT-3.4 | `RecurrenceApiIntegrationTest` | 5 | Medium |
| IT-3.5 | `CategoryApiIntegrationTest` | 7 | Medium |
| IT-3.6 | `CreditCardApiIntegrationTest` | 6 | High |
| IT-3.7 | `DashboardApiIntegrationTest` | 7 | Medium |
| IT-4.1 | `AccountBalanceFlowTest` | 3 | High |
| IT-4.2 | `TransactionSearchFlowTest` | 3 | **Critical** — unicode regression |
| IT-4.3 | `CreditCardLifecycleFlowTest` | 1 | Medium |
| IT-4.4 | `ScheduledJobsFlowTest` | 3 | Medium |
| **Total** | **21 new test files** | **~124 test cases** | |

### Recommended Implementation Order

1. **IT-1.1 + IT-2.1 + IT-3.1** — implement these three together as they all relate to the `lower(bytea)` regression; they also include the fix to `findWithFilters` and `TransactionServiceImpl`.
2. **IT-1.6 + IT-2.4 + IT-3.7** — dashboard queries are the most complex native queries in the codebase and the most likely to have similar type-inference issues.
3. **IT-3.2, IT-3.6** — account and credit card API tests, which complete the financial core.
4. **IT-1.2 through IT-1.5, IT-2.2 through IT-2.3** — fill in remaining repository and service gaps.
5. **IT-4.x** — cross-domain flow tests as a final integration verification layer.

---

*End of Integration Tests Roadmap — cash-control-api v1*