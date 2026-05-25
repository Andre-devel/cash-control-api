# User Stories — Cash Control API

## Overview

This document captures production-grade user stories for the Cash Control API — a personal
finance management backend built on Spring Boot 4 / Java 25, designed for multi-account
balance tracking, transaction lifecycle management, credit card billing, and financial reporting.

The module serves two categories of principals:

- **Authenticated Users**: principals with a valid JWT access token managing their own financial data. All financial data is strictly scoped to the authenticated user — no cross-user access is architecturally possible.
- **System**: automated processes responsible for status recalculation, recurring transaction generation, overdue detection, and scheduled data maintenance.

All stories are MVP-first, implementation-aware, and aligned with the stateless JWT identity model provided by the authentication module, Spring Data JPA repositories, granular user-scoped data isolation, and LGPD privacy-by-design requirements.

---

# 1. Account & Wallet Management

## US-1.1: Create Account

**As an** authenticated user  
**I want to** create a new financial account or wallet  
**So that** I can track the balance and transactions associated with that account

**Acceptance Criteria:**
- [ ] Endpoint requires a valid JWT; the account is created scoped to the authenticated user's UUID.
- [ ] Accepts: name, type (`CHECKING`, `SAVINGS`, `CASH`, `VIRTUAL_WALLET`, `INTERNATIONAL`, `JOINT`, `INVESTMENT`), initial balance (defaults to 0), optional currency code (defaults to `BRL`), and optional description.
- [ ] Name is validated as non-blank and unique per user.
- [ ] Initial balance is stored as a seed `MANUAL_ADJUSTMENT` transaction at account creation time, preserving the balance history from day one.
- [ ] Account is created with active status; `archived_at` is null.
- [ ] HTTP 201 returns the created account DTO; the entity is never exposed directly.

**Expected Result:** A new account is created and immediately available for transactions. The initial balance is recorded as the first transaction in history.

---

## US-1.2: List Accounts

**As an** authenticated user  
**I want to** list all my accounts  
**So that** I can see my financial accounts at a glance and navigate to each one

**Acceptance Criteria:**
- [ ] Returns all non-deleted accounts belonging to the authenticated user.
- [ ] Each account entry includes: UUID, name, type, currency, computed balance, archived status.
- [ ] Archived accounts are excluded by default; included when `includeArchived=true` query param is set.
- [ ] Investment accounts are flagged so the client can separate liquid assets from net worth assets.
- [ ] Result is ordered by user-defined sort order (defaulting to creation date ascending).

**Expected Result:** The user sees their full list of accounts with current balances. Archived accounts are hidden by default.

---

## US-1.3: Edit Account

**As an** authenticated user  
**I want to** update the details of one of my accounts  
**So that** I can correct the name, description, or metadata without recreating the account

**Acceptance Criteria:**
- [ ] Accepts: name, description. Type and initial balance are immutable after creation.
- [ ] Name must remain unique per user; HTTP 409 if a duplicate name results.
- [ ] Currency is immutable after creation to preserve transaction history consistency.
- [ ] Returns the updated account DTO.

**Expected Result:** The account's editable fields are updated. Immutable fields (type, currency) are silently ignored or explicitly rejected.

---

## US-1.4: Archive Account

**As an** authenticated user  
**I want to** archive an account I no longer actively use  
**So that** it is excluded from my active dashboards and balance aggregations while its history is preserved

**Acceptance Criteria:**
- [ ] Sets `archived_at` timestamp on the account record; no physical deletion.
- [ ] Archived accounts are excluded from total balance and cash flow calculations.
- [ ] Historical transactions on the archived account remain queryable and reportable.
- [ ] Archived accounts cannot receive new transactions; attempts return HTTP 422.
- [ ] An archived account can be unarchived at any time, restoring it to active status.

**Expected Result:** The account is hidden from active views and excluded from aggregations. History is fully preserved and the account can be restored.

---

## US-1.5: Delete Account

**As an** authenticated user  
**I want to** permanently delete an account  
**So that** I can remove an account that was created by mistake and has no meaningful transaction history

**Acceptance Criteria:**
- [ ] Deletion is only permitted if the account has no transactions beyond the seed initial balance record.
- [ ] If the account has transactions, deletion is rejected with HTTP 422 and a message suggesting archiving instead.
- [ ] On permitted deletion: the account and its seed record are hard-deleted.
- [ ] The user receives HTTP 204 on success.

**Expected Result:** Empty accounts can be deleted. Accounts with transaction history must be archived, not deleted, to preserve data integrity.

---

## US-1.6: Manual Balance Adjustment

**As an** authenticated user  
**I want to** manually adjust the balance of an account to match its real-world balance  
**So that** I can reconcile the system balance with my actual bank statement after a discrepancy

**Acceptance Criteria:**
- [ ] Accepts: account UUID, target balance (the desired real-world balance), optional notes.
- [ ] The system calculates the difference between the current computed balance and the target balance.
- [ ] A `MANUAL_ADJUSTMENT` transaction is created for the difference amount, with `competenceDate` set to today.
- [ ] The adjustment transaction stores both the before and after balance in its metadata for auditability.
- [ ] The adjustment does not affect cash flow reports (income/expense aggregations exclude `MANUAL_ADJUSTMENT` type).
- [ ] Returns the adjustment transaction and the new computed balance.

**Expected Result:** The account balance is reconciled. The adjustment is transparent in history, distinguishable from normal income/expense transactions, and does not distort cash flow reports.

---

## US-1.7: Transfer Between Accounts

**As an** authenticated user  
**I want to** record a transfer between two of my accounts  
**So that** the money movement is tracked without affecting my net income or expenses

**Acceptance Criteria:**
- [ ] Accepts: source account UUID, destination account UUID, amount, date, optional description.
- [ ] Both accounts must belong to the authenticated user; cross-user transfers are rejected with HTTP 403.
- [ ] Source and destination must be different accounts; same-account transfer is rejected with HTTP 422.
- [ ] Creates two linked transactions atomically: a debit on the source and a credit on the destination, both with `type = TRANSFER` and a shared `transfer_group_id`.
- [ ] The transfer nets to zero at the portfolio level — it does not appear in income or expense totals.
- [ ] If either account is archived, the transfer is rejected with HTTP 422.
- [ ] Returns both linked transactions.

**Expected Result:** The transfer is recorded as a paired debit/credit. Portfolio totals are unaffected. Both transactions are linked for traceability.

---

# 2. Transaction Management

## US-2.1: Create Transaction

**As an** authenticated user  
**I want to** record a new financial transaction  
**So that** my account balance and spending history accurately reflect my real-world financial activity

**Acceptance Criteria:**
- [ ] Accepts: account UUID, type (`INCOME`, `EXPENSE`, `REFUND`), amount (positive), description, `competenceDate`, optional `paymentDate`, optional notes, optional category UUID, optional subcategory UUID, optional tags, optional location, optional status (defaults to `PAID`).
- [ ] Account must belong to the authenticated user.
- [ ] Amount must be positive and greater than zero.
- [ ] If `status = PAID` and no `paymentDate` is provided, `paymentDate` defaults to `competenceDate`.
- [ ] If `status = PENDING`, the transaction does not yet affect the account's settled balance — this is configurable per implementation (cash vs. accrual view).
- [ ] Category and subcategory, if provided, must belong to the authenticated user or be system defaults.
- [ ] HTTP 201 returns the created transaction DTO.

**Expected Result:** The transaction is recorded with all provided data. Balance is updated according to the transaction's status and type.

---

## US-2.2: Edit Transaction

**As an** authenticated user  
**I want to** edit a transaction I previously recorded  
**So that** I can correct mistakes or update missing information

**Acceptance Criteria:**
- [ ] Only transactions belonging to the authenticated user are editable.
- [ ] Editable fields: amount, description, notes, `competenceDate`, `paymentDate`, status, category, subcategory, tags, location.
- [ ] Changing `type` on an existing transaction is not permitted; the user must delete and recreate.
- [ ] If a transaction is part of an installment series, editing it individually detaches it from the series (see US-3.3).
- [ ] Returns the updated transaction DTO.

**Expected Result:** Transaction details are corrected. Changes are reflected immediately in balance and report calculations.

---

## US-2.3: Delete Transaction

**As an** authenticated user  
**I want to** delete a transaction  
**So that** I can remove erroneously recorded entries from my history

**Acceptance Criteria:**
- [ ] Only transactions belonging to the authenticated user are deletable.
- [ ] Transactions that are part of an installment series may be deleted individually; the series record is not affected.
- [ ] Transfer transactions must be deleted as a pair via a dedicated transfer-delete endpoint; deleting one leg individually is rejected with HTTP 422.
- [ ] The account balance is recalculated after deletion.
- [ ] HTTP 204 on success.

**Expected Result:** The transaction is removed. Balance and reports are immediately updated to reflect the deletion.

---

## US-2.4: Mark Transaction as Paid

**As an** authenticated user  
**I want to** mark a pending or overdue transaction as paid  
**So that** my settled balance reflects the actual payment

**Acceptance Criteria:**
- [ ] Accepts: transaction UUID and optional `paymentDate` (defaults to today).
- [ ] Transition is valid from `PENDING` → `PAID` and `OVERDUE` → `PAID`.
- [ ] `paymentDate` is set to the provided date or today's date.
- [ ] The settled balance of the account is updated accordingly.
- [ ] Returns the updated transaction.

**Expected Result:** The transaction is marked paid. Settled account balance reflects the payment.

---

## US-2.5: Cancel Transaction

**As an** authenticated user  
**I want to** cancel a transaction  
**So that** it no longer affects my balance while being preserved in history for reference

**Acceptance Criteria:**
- [ ] Valid from any non-cancelled status.
- [ ] `CANCELLED` transactions do not affect account balance calculations.
- [ ] `CANCELLED` transactions remain visible in history with their cancelled status.
- [ ] `cancelled_at` timestamp is set; no hard deletion.
- [ ] A cancelled transaction cannot be re-activated; the user must create a new transaction.

**Expected Result:** The transaction is cancelled and excluded from balance calculations. The historical record is preserved.

---

## US-2.6: List Transactions

**As an** authenticated user  
**I want to** retrieve a filtered, paginated list of transactions  
**So that** I can browse my financial history and find specific entries

**Acceptance Criteria:**
- [ ] Returns only transactions belonging to the authenticated user.
- [ ] Supports filtering by: account UUID, type, status, category UUID, date range (`competenceDate` or `paymentDate`), tags, amount range.
- [ ] Supports full-text search on description and notes.
- [ ] Results are paginated; default order is `competenceDate` descending.
- [ ] `CANCELLED` transactions are excluded by default; included when `includeCancelled=true`.
- [ ] Each item in the response is a summary DTO; a separate endpoint provides the full transaction detail.

**Expected Result:** The user retrieves a filtered, paginated transaction list. Cancelled transactions are hidden by default.

---

## US-2.7: Attach File to Transaction

**As an** authenticated user  
**I want to** attach a receipt or proof-of-payment file to a transaction  
**So that** I have documentary evidence associated with each spending record

**Acceptance Criteria:**
- [ ] Accepts: transaction UUID and one or more file uploads (receipt, invoice, proof).
- [ ] Supported file types: PDF, PNG, JPG, JPEG. Maximum file size is configurable.
- [ ] Files are stored with access-controlled references; raw file paths are never exposed in API responses.
- [ ] Multiple attachments per transaction are permitted up to a configurable limit.
- [ ] Attachment metadata (UUID, filename, MIME type, size, uploaded timestamp) is returned; never the raw file URL in a public-facing form.
- [ ] Attachments may be deleted individually from a transaction.

**Expected Result:** Files are securely attached to transactions. Metadata is retrievable; file access is controlled.

---

## US-2.8: Automatic Overdue Detection

**As the** system  
**I want to** automatically update transactions to `OVERDUE` status when their payment date passes without settlement  
**So that** users see an accurate representation of unpaid obligations

**Acceptance Criteria:**
- [ ] A scheduled process (or on-demand query-time evaluation) transitions `PENDING` transactions to `OVERDUE` when `paymentDate < today` and status is still `PENDING`.
- [ ] Only transactions with a defined `paymentDate` are subject to overdue detection.
- [ ] `OVERDUE` detection does not modify `paymentDate` or any other field — only `status` changes.
- [ ] The transition is non-destructive and reversible (marking as paid from `OVERDUE` is valid).
- [ ] Overdue transitions are not individually logged as audit events; the status change is implicit in the transaction record.

**Expected Result:** Unpaid transactions past their due date are surfaced as overdue without any user action required.

---

# 3. Installment Transactions

## US-3.1: Create Installment Transaction

**As an** authenticated user  
**I want to** record a purchase split into multiple installments  
**So that** each future payment is individually tracked and reflected in the correct billing month

**Acceptance Criteria:**
- [ ] Accepts: account UUID (or credit card UUID), total amount, number of installments, first installment date, description, optional category, optional notes.
- [ ] An `installment_series` master record is created holding the aggregate terms.
- [ ] Individual transaction records are generated for each installment: `installmentNumber`, `totalInstallments`, per-installment amount (total / installments, with remainder on last), and sequential `paymentDate` (monthly by default).
- [ ] All installment transactions are linked to the series via `installment_series_id`.
- [ ] First installment status may be `PAID` if `firstPaymentDate` is today or past; remaining installments default to `PENDING`.
- [ ] HTTP 201 returns the series record and the list of generated installment transactions.

**Expected Result:** The purchase is split into individually trackable installment records. Each installment appears in the correct month's cash flow.

---

## US-3.2: Edit Entire Installment Series

**As an** authenticated user  
**I want to** edit all remaining unpaid installments of a series at once  
**So that** I can correct the description, category, or account for the entire commitment

**Acceptance Criteria:**
- [ ] Accepts: series UUID and the fields to update (description, notes, category, account).
- [ ] Only future installments with status `PENDING` or `OVERDUE` are updated; already-`PAID` or `CANCELLED` installments are not modified.
- [ ] Amount redistribution across remaining installments is not performed by this endpoint (use early settlement for that).
- [ ] The `installment_series` master record is also updated.
- [ ] Returns the updated series and the count of affected installments.

**Expected Result:** All remaining unpaid installments are updated consistently. Paid installments are preserved as-is.

---

## US-3.3: Edit Individual Installment

**As an** authenticated user  
**I want to** edit a single installment without affecting the rest of the series  
**So that** I can record a specific payment variation (e.g., a late fee on one installment)

**Acceptance Criteria:**
- [ ] Editing an individual installment's amount, date, or notes detaches it from the series: `installment_series_id` is preserved for reference but the installment is flagged as `detached = true`.
- [ ] Detached installments are excluded from series-wide edit operations (US-3.2).
- [ ] The series record reflects the actual remaining installments excluding detached ones.
- [ ] Returns the updated individual installment.

**Expected Result:** The selected installment is updated independently. The series continues to manage the remaining non-detached installments.

---

## US-3.4: Early Settlement (Quitação Antecipada)

**As an** authenticated user  
**I want to** pay off all remaining installments at once before their due dates  
**So that** I can record a full early payoff, optionally at a discounted amount

**Acceptance Criteria:**
- [ ] Accepts: series UUID, settlement amount (may differ from the remaining total for discount), settlement date.
- [ ] All remaining `PENDING` / `OVERDUE` installments are cancelled (`CANCELLED` status).
- [ ] A single new `PAID` transaction is created for the settlement amount, linked to the series via `installment_series_id` and flagged as `early_settlement = true`.
- [ ] The series record is marked `settled` with a `settled_at` timestamp.
- [ ] Returns the settlement transaction and the count of cancelled installments.

**Expected Result:** Remaining installments are cancelled and replaced by a single settlement transaction. The series is closed.

---

## US-3.5: Advance Installment (Antecipação de Parcela)

**As an** authenticated user  
**I want to** move one or more future installments to an earlier payment date  
**So that** I can record a partial advance payment, optionally with an early-payment discount

**Acceptance Criteria:**
- [ ] Accepts: a list of installment UUIDs, new payment date, and optional adjusted amount per installment.
- [ ] All selected installments must belong to the authenticated user and have status `PENDING`.
- [ ] `paymentDate` is updated to the new date; if the new date is today or past and status remains `PENDING`, it transitions to `PAID`.
- [ ] Optional adjusted amount replaces the original installment amount (for early-payment discount).
- [ ] Returns the list of updated installments.

**Expected Result:** Selected installments are moved forward in time. Optional discount amounts are recorded on the individual installment records.

---

# 4. Recurring Transactions

## US-4.1: Create Recurring Transaction

**As an** authenticated user  
**I want to** set up a transaction that repeats on a defined schedule  
**So that** regular income or expenses (salary, rent, subscriptions) are automatically recorded without manual entry each period

**Acceptance Criteria:**
- [ ] Accepts: account UUID, type, amount, description, category, first occurrence date, frequency (`DAILY`, `WEEKLY`, `BIWEEKLY`, `MONTHLY`, `YEARLY`), and optional end date.
- [ ] A `recurrence_rule` record is created with the schedule definition.
- [ ] The first transaction instance is created immediately for the first occurrence date.
- [ ] Subsequent instances are generated proactively (e.g., for the next N periods) or lazily on demand — implementation choice.
- [ ] Open-ended recurrences (no `endDate`) generate indefinitely until paused or deleted.
- [ ] HTTP 201 returns the recurrence rule and the first generated transaction.

**Expected Result:** A recurrence rule is established. The first transaction is immediately visible and future instances are generated according to the schedule.

---

## US-4.2: Edit Recurring Series

**As an** authenticated user  
**I want to** update the terms of a recurring transaction for all future occurrences  
**So that** changes to a subscription price or salary take effect going forward

**Acceptance Criteria:**
- [ ] Accepts: recurrence UUID and updated fields (amount, description, category, account).
- [ ] Changes apply to all future (`PENDING`) instances from the current date forward.
- [ ] Already-`PAID` instances are not modified.
- [ ] The `recurrence_rule` master record is updated.
- [ ] Returns the updated recurrence rule and the count of regenerated/updated future instances.

**Expected Result:** Future recurrences reflect the updated terms. Historical instances are unchanged.

---

## US-4.3: Pause Recurrence

**As an** authenticated user  
**I want to** pause a recurring transaction temporarily  
**So that** no new instances are generated during a period when the recurring obligation is suspended

**Acceptance Criteria:**
- [ ] Accepts: recurrence UUID and optional pause end date.
- [ ] The `recurrence_rule` status is set to `PAUSED`; `paused_at` and optional `resume_at` are stored.
- [ ] No new transaction instances are generated while the rule is paused.
- [ ] Existing `PENDING` instances for the pause period are cancelled.
- [ ] The user may manually resume the recurrence at any time, which restores `ACTIVE` status and re-generates instances from the resume date.

**Expected Result:** The recurring series is paused. No new transactions appear until the user resumes it.

---

## US-4.4: Delete Recurring Series

**As an** authenticated user  
**I want to** permanently delete a recurring transaction rule  
**So that** I can remove a recurrence that is no longer relevant and stop future instance generation

**Acceptance Criteria:**
- [ ] Accepts: recurrence UUID and a deletion strategy: `FUTURE_ONLY` (cancel future pending instances) or `ALL` (cancel all instances including past pending).
- [ ] Already-`PAID` instances are never deleted regardless of strategy.
- [ ] The `recurrence_rule` record is soft-deleted (`deleted_at` set).
- [ ] Returns the count of cancelled instances.

**Expected Result:** The recurrence rule is removed and no new instances will be generated. Historical paid records are preserved.

---

# 5. Category Management

## US-5.1: List Categories

**As an** authenticated user  
**I want to** browse all available categories and subcategories  
**So that** I can assign them to transactions or manage my category structure

**Acceptance Criteria:**
- [ ] Returns all system default categories plus categories created by the authenticated user.
- [ ] Each category includes: UUID, name, color, icon, parent UUID (null for root), isDefault flag, isHidden flag, isArchived flag, display order.
- [ ] Subcategories are nested under their parent in the response structure.
- [ ] Hidden categories are excluded by default; included with `includeHidden=true`.
- [ ] Archived categories are excluded by default; included with `includeArchived=true`.

**Expected Result:** The user sees their full, structured category tree with user-defined and system categories clearly distinguished.

---

## US-5.2: Create Custom Category

**As an** authenticated user  
**I want to** create a custom category or subcategory  
**So that** I can organize transactions according to my personal financial structure

**Acceptance Criteria:**
- [ ] Accepts: name, optional parent category UUID (if provided, creates a subcategory), color (hex), icon identifier.
- [ ] Name must be unique within the same parent scope per user; HTTP 409 on duplicate.
- [ ] Parent UUID, if provided, must belong to a root-level category (subcategories cannot be nested more than two levels).
- [ ] Category is created scoped to the authenticated user.
- [ ] HTTP 201 returns the created category DTO.

**Expected Result:** A new custom category or subcategory is available for use in transactions.

---

## US-5.3: Edit Category

**As an** authenticated user  
**I want to** update the name, color, icon, or display order of one of my categories  
**So that** my category structure accurately reflects my current organizational preferences

**Acceptance Criteria:**
- [ ] Only user-defined categories are editable; system default categories can only have `isHidden` toggled (see US-5.4).
- [ ] Accepts: name, color, icon, display order.
- [ ] Name uniqueness within parent scope is re-validated; HTTP 409 on conflict.
- [ ] Returns the updated category DTO.

**Expected Result:** The category is updated. Changes are immediately reflected in category pickers and reports.

---

## US-5.4: Hide System Category

**As an** authenticated user  
**I want to** hide a default system category I never use  
**So that** my category picker is uncluttered without affecting other users or system integrity

**Acceptance Criteria:**
- [ ] Accepts: category UUID and `isHidden = true/false`.
- [ ] Works on both system default and user-defined categories.
- [ ] Hidden categories do not appear in transaction category pickers.
- [ ] Transactions already categorized under a hidden category retain their assignment; the category is simply excluded from future selection.
- [ ] System categories cannot be deleted — hiding is the maximum action available.

**Expected Result:** The selected category disappears from pickers. Existing categorized transactions are unaffected.

---

## US-5.5: Archive Category

**As an** authenticated user  
**I want to** archive a custom category I no longer use  
**So that** it stops appearing in my pickers while its transaction history is preserved

**Acceptance Criteria:**
- [ ] Only user-defined categories may be archived; system defaults use hiding only.
- [ ] Archived categories cannot be assigned to new transactions; attempts return HTTP 422.
- [ ] Transactions already assigned to the archived category retain their assignment.
- [ ] Archiving a parent category also archives all its subcategories.
- [ ] Archived categories can be unarchived at any time.

**Expected Result:** The archived category is excluded from transaction pickers. Historical categorizations are intact.

---

## US-5.6: Auto-Suggest Category

**As an** authenticated user  
**I want to** receive an automatic category suggestion when I type a transaction description  
**So that** categorization is faster and more consistent across similar transactions

**Acceptance Criteria:**
- [ ] Endpoint accepts a partial or complete transaction description string.
- [ ] Returns a ranked list of suggested categories based on the user's prior categorization history for similar descriptions.
- [ ] If no history exists, returns the most commonly used categories for the user.
- [ ] Suggestions include: category UUID, name, and a confidence score.
- [ ] The suggestion is non-binding — the user may override it freely.

**Expected Result:** Category suggestions reduce manual categorization effort and improve consistency.

---

## US-5.7: Create Category Rule

**As an** authenticated user  
**I want to** create a rule that automatically assigns a category to transactions matching a description pattern  
**So that** recurring merchants or payees are always categorized correctly without manual intervention

**Acceptance Criteria:**
- [ ] Accepts: keyword or pattern, target category UUID, optional subcategory UUID, optional account scope.
- [ ] Rules are applied at transaction creation time when the description matches the pattern.
- [ ] Multiple rules may match; priority order is user-configurable.
- [ ] Rules are scoped to the authenticated user.
- [ ] If a rule auto-assigns a category, the user may override it on the individual transaction.
- [ ] HTTP 201 returns the created rule.

**Expected Result:** Matching transactions are auto-categorized. The user's categorization intent is encoded in reusable rules.

---

# 6. Credit Card Management

## US-6.1: Create Credit Card

**As an** authenticated user  
**I want to** add a credit card to my financial profile  
**So that** I can track charges, invoices, and limit usage for that card

**Acceptance Criteria:**
- [ ] Accepts: name, brand (`VISA`, `MASTERCARD`, `ELO`, `AMEX`, `HIPERCARD`, `OTHER`), issuer name, credit limit, closing day (1–28), due day (1–28), optional `sharedLimitGroupId`.
- [ ] Name must be unique per user; HTTP 409 on duplicate.
- [ ] `closingDay` and `dueDay` are validated as valid day-of-month values.
- [ ] The first open invoice is automatically created on card creation for the current billing cycle.
- [ ] HTTP 201 returns the created card DTO.

**Expected Result:** A new credit card is registered. Its first invoice cycle is immediately open for charges.

---

## US-6.2: Record Credit Card Expense

**As an** authenticated user  
**I want to** record a credit card charge  
**So that** it is assigned to the correct billing invoice and reflected in my limit usage

**Acceptance Criteria:**
- [ ] Accepts: card UUID, amount, description, `competenceDate`, optional category, optional subcategory, optional tags, optional notes.
- [ ] The charge is assigned to the correct invoice based on `competenceDate` relative to the card's `closingDay`.
- [ ] If `competenceDate` falls after the current cycle's closing date, the charge is assigned to the next invoice.
- [ ] The card's used limit is updated in real time.
- [ ] HTTP 201 returns the charge transaction and its assigned invoice reference.

**Expected Result:** The charge is recorded under the correct invoice cycle. Limit usage is updated immediately.

---

## US-6.3: View Invoice

**As an** authenticated user  
**I want to** view the details of a credit card invoice  
**So that** I can see all charges for a billing cycle, the total due, and the payment status

**Acceptance Criteria:**
- [ ] Accepts: card UUID and optional `referenceMonth` (defaults to current open invoice).
- [ ] Returns: invoice UUID, reference month, closing date, due date, total amount, status, paid amount, list of charge transactions.
- [ ] Charge transactions are paginated within the invoice response.
- [ ] Future installment charges attributed to this invoice are included with a `FUTURE` flag.
- [ ] The response distinguishes between the open (current) invoice and closed/paid invoices.

**Expected Result:** The user has a clear, itemized view of each billing cycle including all charges, totals, and payment status.

---

## US-6.4: Pay Invoice

**As an** authenticated user  
**I want to** record payment of a credit card invoice  
**So that** the invoice status reflects the payment and the corresponding debit is registered on my bank account

**Acceptance Criteria:**
- [ ] Accepts: invoice UUID, paid amount, payment date, source account UUID (the bank account debited for the payment).
- [ ] If `paidAmount == invoice.totalAmount`: invoice status transitions to `PAID`.
- [ ] If `paidAmount < invoice.totalAmount`: invoice status transitions to `PARTIAL`; the unpaid remainder is recorded as a `REVOLVING` charge added to the next invoice.
- [ ] A debit transaction is created on the source account for the `paidAmount`.
- [ ] Returns the updated invoice and the generated debit transaction.

**Expected Result:** The payment is recorded. Full payment closes the invoice; partial payment moves the remainder to the next cycle as revolving credit.

---

## US-6.5: View Limit Usage

**As an** authenticated user  
**I want to** see my current limit usage and available credit for each card  
**So that** I know how much credit I have left before making a new purchase

**Acceptance Criteria:**
- [ ] Returns for each card: total limit, used amount (charges in current open invoice + pending installments on future invoices), available limit.
- [ ] Available limit = total limit − used amount.
- [ ] For shared-limit groups, the group-level available limit is also shown.
- [ ] Updates in real time after every charge or payment.

**Expected Result:** The user always sees an up-to-date snapshot of their credit availability per card.

---

## US-6.6: List Card Spending by Category

**As an** authenticated user  
**I want to** see a breakdown of my credit card spending by category for a given period  
**So that** I can understand where my card expenses are concentrated

**Acceptance Criteria:**
- [ ] Accepts: card UUID, date range (defaults to current invoice cycle).
- [ ] Returns: list of categories, total amount per category, percentage of total spending.
- [ ] Uncategorized charges are grouped under a special `UNCATEGORIZED` entry.
- [ ] Results are sorted by amount descending.

**Expected Result:** The user sees a ranked category breakdown of their card spending for the selected period.

---

# 7. Dashboard & Reporting

## US-7.1: Dashboard Overview Metrics

**As an** authenticated user  
**I want to** see aggregated financial health metrics on my dashboard  
**So that** I have an immediate snapshot of my current financial position

**Acceptance Criteria:**
- [ ] Returns all metrics scoped to the authenticated user.
- [ ] **Total balance**: sum of all non-archived, non-investment account balances (settled transactions only).
- [ ] **Total net worth**: total balance + investment account balances.
- [ ] **Monthly income**: sum of `PAID` `INCOME` transactions with `paymentDate` in the current calendar month.
- [ ] **Monthly expenses**: sum of `PAID` `EXPENSE` transactions with `paymentDate` in the current calendar month.
- [ ] **Monthly savings**: monthly income minus monthly expenses.
- [ ] **Cash flow**: net movement (income − expense) over a configurable window (default: current month).
- [ ] All monetary values use `BigDecimal` string representation; no floating-point.

**Expected Result:** A single endpoint returns the complete financial health snapshot. All figures are scoped to the authenticated user and computed on demand from transaction history.

---

## US-7.2: Category Pie Chart Data

**As an** authenticated user  
**I want to** retrieve spending distribution by category for a given period  
**So that** I can visualize where my money is going

**Acceptance Criteria:**
- [ ] Accepts: date range, optional account UUID filter, transaction type filter (default: `EXPENSE`).
- [ ] Returns: list of categories with total amount and percentage of total for the period.
- [ ] Only `PAID` transactions are included; `PENDING`, `OVERDUE`, and `CANCELLED` are excluded.
- [ ] Transactions without a category are grouped under `UNCATEGORIZED`.
- [ ] Subcategories roll up to their parent category unless `groupBySubcategory=true` is set.
- [ ] Results are sorted by amount descending.

**Expected Result:** A dataset ready for pie chart rendering, showing the proportional breakdown of spending by category.

---

## US-7.3: Monthly Bar Chart Data

**As an** authenticated user  
**I want to** retrieve monthly income and expense totals for a range of months  
**So that** I can visualize my spending and earning trends over time

**Acceptance Criteria:**
- [ ] Accepts: number of months (default: 6, max: 24), optional account UUID filter.
- [ ] Returns: one entry per month with: month label (YYYY-MM), total income, total expenses, net (income − expenses).
- [ ] Only `PAID` transactions are included.
- [ ] Months with no transactions are included with zero values (no gaps in the timeline).
- [ ] Ordered chronologically, oldest to newest.

**Expected Result:** A month-by-month dataset for bar chart rendering, covering the requested historical window.

---

## US-7.4: Net Worth Evolution Chart Data

**As an** authenticated user  
**I want to** retrieve the evolution of my net worth over time  
**So that** I can track my overall financial progress

**Acceptance Criteria:**
- [ ] Accepts: date range and granularity (`DAILY`, `WEEKLY`, `MONTHLY`).
- [ ] Returns: one entry per period with a computed net worth snapshot (total balance + investments at that point in time).
- [ ] Net worth is computed by replaying the transaction history up to each snapshot date.
- [ ] Only `PAID` transactions contribute to historical balance calculations.
- [ ] Results are ordered chronologically.

**Expected Result:** A time-series dataset for line chart rendering showing how total net worth has changed over the selected period.

---

## US-7.5: Monthly Comparison Data

**As an** authenticated user  
**I want to** compare income and expenses between two selected months  
**So that** I can understand how my financial behavior has changed period-over-period

**Acceptance Criteria:**
- [ ] Accepts: two month references (YYYY-MM format).
- [ ] Returns: for each month — total income, total expenses, savings, and a per-category expense breakdown.
- [ ] Delta values (difference and percentage change) are computed for each metric.
- [ ] Only `PAID` transactions are included.

**Expected Result:** A side-by-side comparison dataset showing income, expenses, and category-level changes between the two months.

---

## US-7.6: Upcoming Bills Widget

**As an** authenticated user  
**I want to** see a list of pending and overdue transactions due in the near future  
**So that** I can plan payments and avoid missing due dates

**Acceptance Criteria:**
- [ ] Returns `PENDING` and `OVERDUE` transactions where `paymentDate <= today + configurable window` (default: 7 days).
- [ ] Results are ordered by `paymentDate` ascending (most urgent first).
- [ ] Each entry includes: amount, description, account name, category, `paymentDate`, status.
- [ ] Optional `daysAhead` parameter allows the user to extend or narrow the window.
- [ ] Limited to a maximum of N results (configurable, default 20) for widget purposes.

**Expected Result:** The user sees an urgency-ranked list of upcoming obligations, enabling proactive cash flow management.

---

## US-7.7: Upcoming Invoices Widget

**As an** authenticated user  
**I want to** see a list of credit card invoices due in the near future  
**So that** I can ensure sufficient funds are available for upcoming card payments

**Acceptance Criteria:**
- [ ] Returns all credit card invoices with `dueDate <= today + configurable window` and status in (`CLOSED`, `PARTIAL`, `OVERDUE`).
- [ ] Ordered by `dueDate` ascending.
- [ ] Each entry includes: card name, total amount, paid amount, remaining amount, due date, status.
- [ ] Already-`PAID` invoices are excluded.

**Expected Result:** The user sees upcoming card payment obligations ranked by urgency.

---

## US-7.8: Largest Expenses Widget

**As an** authenticated user  
**I want to** see my largest expense transactions in the current period  
**So that** I can quickly identify unusual or high-value spending

**Acceptance Criteria:**
- [ ] Returns the top N `PAID` `EXPENSE` transactions by amount for the current calendar month (configurable N, default 5).
- [ ] Each entry includes: amount, description, category, account name, `paymentDate`.
- [ ] Optional `period` parameter accepts a custom date range.
- [ ] Only transactions belonging to the authenticated user are included.

**Expected Result:** The user sees a ranked list of their biggest expenses for the selected period.

---

## US-7.9: Recent Transactions Widget

**As an** authenticated user  
**I want to** see my most recent transactions across all accounts  
**So that** I have immediate visibility into my latest financial activity

**Acceptance Criteria:**
- [ ] Returns the N most recent transactions ordered by `competenceDate` descending (configurable N, default 10).
- [ ] Includes transactions from all non-archived accounts of the authenticated user.
- [ ] Each entry includes: amount, description, type, status, account name, category, `competenceDate`.
- [ ] `CANCELLED` transactions are excluded.

**Expected Result:** The user sees a cross-account feed of their latest financial activity at a glance.

---

# 8. Privacy & LGPD Compliance

## US-8.1: Data Scoping and User Isolation

**As the** system  
**I want to** enforce strict user-scoped data isolation on all financial data access  
**So that** no user can access, modify, or infer another user's financial data under any circumstances

**Acceptance Criteria:**
- [ ] Every repository query includes a `user_id = :currentUserId` predicate derived from the JWT subject claim.
- [ ] Service methods validate resource ownership before any operation; HTTP 403 is returned (not 404) when a user attempts to access a resource belonging to another user.
- [ ] No endpoint accepts a `userId` parameter from the request body or query string to scope data — the user identity is always derived from the authenticated JWT.
- [ ] Database-level: all financial tables have a `user_id` foreign key with a NOT NULL constraint.

**Expected Result:** Financial data isolation is architecturally guaranteed. Cross-user data access is impossible regardless of client behavior.

---

## US-8.2: Financial Data Not Included in Logs

**As the** system  
**I want to** ensure financial data (amounts, descriptions, account names) is never emitted in application logs  
**So that** the logging infrastructure does not create a LGPD data exposure risk

**Acceptance Criteria:**
- [ ] Transaction amounts, descriptions, and account balances are never logged at any log level.
- [ ] Category names and tag values are not included in log output.
- [ ] Logs reference resources by UUID only (e.g., `transactionId=<UUID>`) — never by human-readable content.
- [ ] A centralized log sanitization utility enforces these rules; ad hoc per-class masking is not sufficient.

**Expected Result:** Log infrastructure contains operational metadata (UUIDs, event types, correlation IDs) but no financial content data.

---

## US-8.3: Soft-Delete for LGPD Erasure Readiness

**As an** authenticated user  
**I want to** be able to delete my account  
**So that** my personal and financial data can be removed in compliance with LGPD right-to-erasure

**Acceptance Criteria:**
- [ ] Account deletion sets a `deleted_at` timestamp on the user's record in the auth module; no immediate hard deletion.
- [ ] Financial data (accounts, transactions, categories) is soft-deleted: `deleted_at` timestamps are set across all user-scoped tables.
- [ ] A documented anonymization pipeline can zero out PII-adjacent fields (account names, transaction descriptions) while preserving UUIDs and aggregate statistics for internal reporting.
- [ ] After soft-deletion, the user cannot authenticate or access any financial data.

**Expected Result:** Data is retained for internal integrity after logical deletion. A clear path exists for LGPD-compliant PII anonymization on erasure requests.

---

## US-8.4: Data Portability Export

**As an** authenticated user  
**I want to** export all my financial data  
**So that** I can exercise my LGPD right to data portability

**Acceptance Criteria:**
- [ ] Export endpoint is available to any authenticated user for their own data only.
- [ ] Returns a structured export (JSON or CSV) containing: accounts, transactions (with all fields), categories, recurrence rules, credit cards, invoices.
- [ ] Sensitive operational fields (internal UUIDs of system records) are included; password hashes and auth tokens are excluded.
- [ ] Export is generated asynchronously for large datasets; the user is notified when ready.
- [ ] The export file is accessible only to the authenticated user who requested it.

**Expected Result:** The user receives a complete, structured export of all their financial data in a portable format.

---

# 9. Non-Functional & Architecture Requirements

## US-9.1: Monetary Precision

**As the** system  
**I want to** perform all monetary calculations using `BigDecimal` arithmetic  
**So that** no financial values are subject to floating-point rounding errors

**Acceptance Criteria:**
- [ ] All amount fields use `BigDecimal` with a scale of 2 (currency precision) throughout the stack: database (NUMERIC), JPA entity, DTO, and API response.
- [ ] Arithmetic operations (sum, division for installment split, percentage calculation) use `BigDecimal` methods with explicit rounding modes (`HALF_UP`).
- [ ] No `double` or `float` types are used for monetary values anywhere in the codebase.
- [ ] Division remainders (e.g., installment amounts that don't divide evenly) are handled deterministically: remainder assigned to the first or last installment consistently.
- [ ] API responses serialize amounts as strings (e.g., `"150.75"`) to prevent client-side floating-point loss.

**Expected Result:** Financial calculations are exact. No rounding errors accumulate across transactions or installment series.

---

## US-9.2: Enforced Layered Architecture

**As a** developer  
**I want to** work within a clearly enforced layered architecture  
**So that** responsibilities are separated, the codebase is maintainable, and the financial domain logic is extensible

**Acceptance Criteria:**
- [ ] Four enforced layers: Controller, Service, Repository, Domain.
- [ ] JPA entities never cross the service boundary into response DTOs; the API layer uses only DTOs.
- [ ] Services depend on repository interfaces; no concrete repository references in business logic.
- [ ] A global `@ControllerAdvice` maps all domain exceptions to appropriate HTTP responses uniformly.
- [ ] No business logic exists in controllers or repositories.
- [ ] Financial calculations (balance aggregation, installment generation, recurrence expansion) live exclusively in the service layer.

**Expected Result:** The codebase is maintainable and testable. Financial logic is centralized and protected from infrastructure-layer concerns.

---

## US-9.3: Balance Consistency

**As the** system  
**I want to** maintain a consistent computed account balance at all times  
**So that** the balance shown to the user always reflects the actual sum of their transaction history

**Acceptance Criteria:**
- [ ] Account balance is computed by summing the signed amounts of all `PAID` transactions (income positive, expense/transfer-debit negative, refund positive, manual-adjustment as-signed).
- [ ] Balance is recomputed and consistent after every transaction creation, edit, deletion, or status change.
- [ ] `PENDING`, `OVERDUE`, and `CANCELLED` transactions are excluded from the settled balance.
- [ ] A separate `pendingBalance` may be provided in account details showing what the balance would be if all pending transactions settled.
- [ ] Balance integrity is verified by the test suite with known transaction sequences.

**Expected Result:** Account balances are always exact and consistent with the underlying transaction history.

---

## US-9.4: Input Validation at the API Boundary

**As the** system  
**I want to** validate all incoming request DTOs before any business logic executes  
**So that** malformed input is rejected early and never reaches the service layer

**Acceptance Criteria:**
- [ ] All request DTOs use Jakarta Validation: `@NotNull`, `@NotBlank`, `@Positive`, `@Size`, `@DecimalMin`, custom domain constraints.
- [ ] `@Valid` is applied to all controller method parameters.
- [ ] Validation failures return HTTP 400 with a structured field-level error body.
- [ ] Amount fields are validated as positive and within a maximum configurable value.
- [ ] Date fields are validated for coherence: e.g., `paymentDate` must not precede `competenceDate` by more than a configurable threshold.
- [ ] The 400 body never exposes stack traces or internal class names.

**Expected Result:** Invalid input is rejected at the controller boundary with a clean 400. The service layer processes only validated data.

---

## US-9.5: Standardized API Response Envelope

**As an** API consumer  
**I want to** receive consistent, predictable response structures from all endpoints  
**So that** client-side handling is uniform and self-describing

**Acceptance Criteria:**
- [ ] All error responses follow: `{ "errorCode": "...", "message": "...", "correlationId": "..." }`.
- [ ] HTTP status codes follow REST conventions: 200/201 (success), 400 (validation), 401 (authentication), 403 (authorization), 404 (not found), 409 (conflict), 422 (business rule violation), 500 (server error).
- [ ] 422 is used for business rule violations (e.g., transfer to archived account, delete non-empty account) to distinguish from validation errors (400).
- [ ] 500 responses never expose stack traces, exception class names, or database error messages.
- [ ] All timestamps are returned in ISO 8601 UTC format.

**Expected Result:** API consumers rely on a consistent response structure. Internal implementation details are never leaked via error responses.

---

## US-9.6: Docker and Cloud-Native Deployment Readiness

**As a** DevOps engineer  
**I want to** deploy the Cash Control API as a containerized service  
**So that** it integrates into Docker and Kubernetes-based infrastructure

**Acceptance Criteria:**
- [ ] A `Dockerfile` is provided using a multi-stage build optimized for image size and security.
- [ ] All secrets and environment-specific configuration are injected via environment variables (12-factor app).
- [ ] A `docker-compose.yml` is available for local development with PostgreSQL.
- [ ] The application exposes `/actuator/health` for liveness and readiness probes.
- [ ] Flyway migrations run on startup in development; production startup behavior is configurable.
- [ ] Application fails fast with a clear error message if required environment variables are absent.

**Expected Result:** The module is deployable in any container environment. Configuration is fully externalized and the service is orchestrator-ready.

---

## US-9.7: Database Schema Management via Flyway

**As a** developer or DBA  
**I want to** manage all database schema changes through Flyway versioned migrations  
**So that** schema evolution is tracked, reproducible, and safe for production deployments

**Acceptance Criteria:**
- [ ] Flyway is the sole mechanism for schema changes; `ddl-auto` is `validate` or `none` in production.
- [ ] All migrations are versioned (`V{n}__{description}.sql`) in `src/main/resources/db/migration`.
- [ ] The baseline migration defines all tables with correct `NUMERIC` amount columns, UUID PKs, foreign keys, NOT NULL constraints, and indexes.
- [ ] Indexes are defined in migrations: `user_id` on all financial tables, `transactions.competence_date`, `transactions.payment_date`, `transactions.status`, `invoices.due_date`, `recurrence_rules.next_occurrence_date`.
- [ ] Migration checksums are validated on startup; tampered migrations block application startup.

**Expected Result:** The database schema is fully version-controlled and reproducible across all environments.

---

## US-9.8: Financial Domain Test Suite

**As a** developer  
**I want to** have a comprehensive test suite covering financial calculations and domain rules  
**So that** financial logic regressions are caught before production

**Acceptance Criteria:**
- [ ] Unit tests cover: balance calculation from transaction history, installment generation (amount split, remainder assignment), recurrence expansion, category rule matching, invoice cycle assignment logic.
- [ ] Integration tests cover: full transaction lifecycle (create → edit → pay → cancel), installment series creation and early settlement, transfer atomicity (both legs or neither), manual adjustment reconciliation.
- [ ] Boundary tests verify: `BigDecimal` precision in installment amount splits, balance consistency after concurrent-scenario transaction sequences, overdue status detection logic.
- [ ] Integration tests use a real PostgreSQL instance (Testcontainers) — no mocked repositories for core financial flows.
- [ ] The test suite runs in CI/CD and gates merges and deployments.

**Expected Result:** Financial calculation correctness is continuously verified. Precision and domain-rule regressions are blocked before production.
