## Phase 10 — Dashboard & Reporting

**Objective:** Implement all dashboard aggregation endpoints and chart data endpoints.

**Dependencies:** Phases 4–9 complete.

**Complexity:** Medium

### Phase 10.1 — Dashboard Service

**Implementation Tasks:**

- [ ] Create `DashboardService.java` and `DashboardServiceImpl.java`:
  - `getOverviewMetrics(UUID userId)`:
    - Total balance: sum of `PAID` transactions across all non-archived, non-investment accounts
    - Net worth: total balance + investment account balances
    - Monthly income: `PAID` `INCOME` transactions with `paymentDate` in current calendar month
    - Monthly expenses: `PAID` `EXPENSE` transactions with `paymentDate` in current calendar month
    - Monthly savings: income − expenses
    - Cash flow: configurable window (default: current month)
    - All amounts as `BigDecimal`; no floating-point
  - `getCategoryPieChart(UUID userId, DateRange, UUID accountId, TransactionType)`:
    - `PAID` transactions only; grouped by category; percentage of total; sorted by amount desc
    - Uncategorized → `UNCATEGORIZED` bucket
  - `getMonthlyBarChart(UUID userId, int months, UUID accountId)`:
    - One entry per month; months with no transactions filled with zero values; ordered chronologically
  - `getNetWorthEvolution(UUID userId, DateRange, Granularity)`:
    - Replays transaction history up to each snapshot date
  - `getMonthlyComparison(UUID userId, String month1, String month2)`:
    - Income, expenses, savings, category breakdown for each; delta values computed
  - `getUpcomingBills(UUID userId, int daysAhead)` — `PENDING`/`OVERDUE`, `paymentDate ≤ today + daysAhead`
  - `getUpcomingInvoices(UUID userId, int daysAhead)` — invoices with `dueDate ≤ today + daysAhead`, status `CLOSED`/`PARTIAL`/`OVERDUE`
  - `getLargestExpenses(UUID userId, DateRange, int limit)`
  - `getRecentTransactions(UUID userId, int limit)`

**Acceptance Criteria:**
- [ ] All metrics scoped to the authenticated user; no cross-user data
- [ ] All monetary values use `BigDecimal` string representation in responses
- [ ] Months with no transactions included with zero values in bar chart
- [ ] Net worth evolution replays history accurately

**Automated Tests:**
- [ ] `DashboardServiceTest` — unit tests with seeded transaction data for each metric
- [ ] `OverviewMetricsIntegrationTest` — asserts correct totals against known transaction sequences

---

### Phase 10.2 — Dashboard Controller

**Implementation Tasks:**

- [ ] Create `DashboardController.java` — `@RestController @RequestMapping("/api/v1/dashboard")`:
  - `GET /overview` → overview metrics → 200
  - `GET /charts/categories` → pie chart data → 200
  - `GET /charts/monthly` → bar chart data → 200
  - `GET /charts/net-worth` → net worth evolution → 200
  - `GET /charts/comparison` → monthly comparison → 200
  - `GET /widgets/upcoming-bills` → 200
  - `GET /widgets/upcoming-invoices` → 200
  - `GET /widgets/largest-expenses` → 200
  - `GET /widgets/recent-transactions` → 200

**Automated Tests:**
- [ ] `DashboardControllerTest` — HTTP validation for all endpoints

---

