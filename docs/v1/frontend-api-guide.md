# Cash Control API — Frontend Implementation Guide

**OpenAPI JSON:** `http://localhost:8080/v3/api-docs`  
**API version prefix:** `/api/v1`

---

## Authentication

All financial endpoints require a JWT Bearer Token. Obtain the token via login and include it in every protected request:

```
Authorization: Bearer <token>
```

Tokens expire (default: 15 min). On receiving `401`, redirect the user to login — there is no refresh token mechanism.

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/auth/register` | Register a new user account |
| `POST` | `/api/v1/auth/login` | Login — returns `{ accessToken, tokenType, expiresIn }` |
| `POST` | `/api/v1/auth/logout` | Logout |
| `GET`  | `/api/v1/auth/me` | Get authenticated user profile |
| `POST` | `/api/v1/auth/password/change` | Change password |
| `POST` | `/api/v1/auth/password-reset/request` | Request password reset by e-mail |
| `POST` | `/api/v1/auth/password-reset/confirm` | Complete password reset with e-mail token |
| `GET`  | `/api/v1/auth/email/verify?token=...` | Verify e-mail address (link received by e-mail) |
| `POST` | `/api/v1/auth/email/verify/resend` | Resend e-mail verification |
| `POST` | `/api/v1/auth/email/change` | Request e-mail change |
| `DELETE` | `/api/v1/auth/provider/{providerSlug}` | Unlink OAuth2 provider (e.g. `google`) |

**Registration flow:**
1. `POST /register` → user created with status `PENDING_VERIFICATION`
2. User clicks the e-mail link → `GET /email/verify?token=...`
3. Status changes to `ACTIVE` → user can log in

---

## User Profile

| Method | Path | Description |
|--------|------|-------------|
| `GET`  | `/api/v1/users/me` | Get profile data |
| `PUT`  | `/api/v1/users/me` | Update name and preferences |
| `GET`  | `/api/v1/users/me/consents` | Consent history |

---

## Accounts

Accounts represent wallets, bank accounts, savings, etc.

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/accounts` | Create account |
| `GET`  | `/api/v1/accounts` | List accounts (`?includeArchived=false`) |
| `GET`  | `/api/v1/accounts/{id}` | Get account details |
| `PUT`  | `/api/v1/accounts/{id}` | Edit account |
| `DELETE` | `/api/v1/accounts/{id}` | Delete account |
| `POST` | `/api/v1/accounts/{id}/archive` | Archive account |
| `POST` | `/api/v1/accounts/{id}/unarchive` | Unarchive account |
| `POST` | `/api/v1/accounts/{id}/adjust` | Manual balance adjustment |
| `POST` | `/api/v1/accounts/transfers` | Create transfer between accounts |
| `DELETE` | `/api/v1/accounts/transfers/{groupId}` | Undo transfer |

**`AccountResponse` fields:** `id`, `name`, `type`, `balance`, `currency`, `color`, `icon`, `archived`, `createdAt`

**Account types (`type`):** `CHECKING`, `SAVINGS`, `CASH`, `INVESTMENT`, `CREDIT`, `OTHER`

---

## Transactions

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/transactions` | Create transaction |
| `GET`  | `/api/v1/transactions` | List with filters (paginated) |
| `GET`  | `/api/v1/transactions/{id}` | Get transaction details |
| `PUT`  | `/api/v1/transactions/{id}` | Edit transaction |
| `DELETE` | `/api/v1/transactions/{id}` | Delete transaction |
| `POST` | `/api/v1/transactions/{id}/pay` | Mark as paid |
| `POST` | `/api/v1/transactions/{id}/cancel` | Cancel transaction |
| `POST` | `/api/v1/transactions/{id}/attachments` | Upload attachments (`multipart/form-data`) |
| `GET`  | `/api/v1/transactions/{id}/attachments` | List attachments |
| `DELETE` | `/api/v1/transactions/{id}/attachments/{attachmentId}` | Delete attachment |

**Available filters for `GET /transactions`:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `accountId` | UUID | Filter by account |
| `type` | enum | `INCOME`, `EXPENSE`, `TRANSFER`, `REFUND`, `ADJUSTMENT` |
| `status` | enum | `PENDING`, `PAID`, `CANCELLED` |
| `categoryId` | UUID | Filter by category |
| `competenceDateFrom` / `competenceDateTo` | date | Competence date range |
| `paymentDateFrom` / `paymentDateTo` | date | Payment date range |
| `amountMin` / `amountMax` | decimal | Amount range |
| `searchText` | string | Full-text search |
| `includeCancelled` | boolean | Include cancelled transactions (default `false`) |

**Paginated response shape:** `{ content: [], totalElements, totalPages, number, size }`

> **Note:** Monetary values are decimal strings (e.g. `"150.75"`) to avoid floating-point precision loss on the client.

---

## Installments

Installments create a linked series of transactions.

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/installments` | Create installment series |
| `PUT`  | `/api/v1/installments/series/{seriesId}` | Edit entire series |
| `PUT`  | `/api/v1/installments/{transactionId}` | Edit individual installment |
| `POST` | `/api/v1/installments/series/{seriesId}/settle` | Early settlement |
| `POST` | `/api/v1/installments/advance` | Advance installments |

**`CreateInstallmentRequest` fields:** `description`, `totalAmount`, `installmentCount`, `accountId`, `categoryId`, `firstDueDate`, `type`

---

## Recurrences

Transactions that repeat automatically on a defined frequency.

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/recurrences` | Create recurrence rule |
| `GET`  | `/api/v1/recurrences` | List active rules |
| `GET`  | `/api/v1/recurrences/{id}` | Get rule details |
| `PUT`  | `/api/v1/recurrences/{id}` | Edit recurrence series |
| `POST` | `/api/v1/recurrences/{id}/pause` | Pause recurrence |
| `POST` | `/api/v1/recurrences/{id}/resume` | Resume recurrence |
| `DELETE` | `/api/v1/recurrences/{id}?strategy=FUTURE_ONLY` | Delete rule (`FUTURE_ONLY` or `ALL`) |

**Frequency values (`frequency`):** `DAILY`, `WEEKLY`, `BIWEEKLY`, `MONTHLY`, `QUARTERLY`, `YEARLY`

---

## Categories

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/categories` | Create category |
| `GET`  | `/api/v1/categories` | List categories (`?includeHidden=false&includeArchived=false`) |
| `PUT`  | `/api/v1/categories/{id}` | Edit category |
| `POST` | `/api/v1/categories/{id}/hide` | Hide category |
| `POST` | `/api/v1/categories/{id}/show` | Show category |
| `POST` | `/api/v1/categories/{id}/archive` | Archive category |
| `POST` | `/api/v1/categories/{id}/unarchive` | Unarchive category |
| `GET`  | `/api/v1/categories/suggest?description=...` | Auto-suggest category |
| `POST` | `/api/v1/categories/rules` | Create auto-categorization rule |
| `GET`  | `/api/v1/categories/rules` | List rules |
| `DELETE` | `/api/v1/categories/rules/{id}` | Delete rule |

**`CategoryResponse` fields:** `id`, `name`, `color`, `icon`, `parentId`, `hidden`, `archived`, `type`

**Categories support hierarchy** (parent/child via `parentId`).

---

## Credit Cards

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/cards` | Create credit card |
| `GET`  | `/api/v1/cards` | List credit cards |
| `PUT`  | `/api/v1/cards/{id}` | Edit credit card |
| `POST` | `/api/v1/cards/{id}/archive` | Archive credit card |
| `POST` | `/api/v1/cards/{id}/charges` | Record a charge on the card |
| `GET`  | `/api/v1/cards/{id}/invoices/{referenceMonth}` | Get invoice for a month (`YYYY-MM`) |
| `POST` | `/api/v1/cards/invoices/{invoiceId}/pay` | Pay invoice (full or partial) |
| `GET`  | `/api/v1/cards/{id}/limit` | Get limit usage |
| `GET`  | `/api/v1/cards/{id}/spending?from=...&to=...` | Spending breakdown by category |

**`CreditCardResponse` fields:** `id`, `name`, `brand`, `lastFourDigits`, `creditLimit`, `billingCycleDay`, `dueDay`, `color`, `archived`

**`InvoiceResponse` fields:** `id`, `referenceMonth`, `totalAmount`, `paidAmount`, `remainingAmount`, `status`, `items[]`

---

## Dashboard

Aggregated data for the main screen.

| Method | Path | Required params | Description |
|--------|------|-----------------|-------------|
| `GET` | `/api/v1/dashboard/overview` | — | Total balance, monthly income/expenses, active accounts |
| `GET` | `/api/v1/dashboard/charts/categories` | `from`, `to` (date) | Spending by category (pie chart data) |
| `GET` | `/api/v1/dashboard/charts/monthly` | — (`?months=6`) | Income vs expense per month (bar chart data) |
| `GET` | `/api/v1/dashboard/charts/net-worth` | `from`, `to` (date) | Net worth evolution over time |
| `GET` | `/api/v1/dashboard/charts/comparison` | `month1`, `month2` (YYYY-MM) | Side-by-side month comparison |
| `GET` | `/api/v1/dashboard/widgets/upcoming-bills` | — (`?daysAhead=7`) | Bills due soon |
| `GET` | `/api/v1/dashboard/widgets/upcoming-invoices` | — | Credit card invoices due soon |
| `GET` | `/api/v1/dashboard/widgets/largest-expenses` | — (`?limit=5`) | Largest expenses in the period |
| `GET` | `/api/v1/dashboard/widgets/recent-transactions` | — (`?limit=10`) | Most recent transactions |

---

## Error Format

Every error response (4xx/5xx) uses the same envelope:

```json
{
  "errorCode": "RESOURCE_NOT_FOUND",
  "message": "Account not found.",
  "correlationId": "550e8400-e29b-41d4-a716-446655440000",
  "timestamp": "2026-05-27T10:00:00Z"
}
```

**Common error codes:**

| HTTP | `errorCode` | When |
|------|-------------|------|
| 400 | `VALIDATION_ERROR` | Invalid or missing field |
| 401 | `UNAUTHORIZED` | Token missing, expired, or invalid |
| 403 | `FORBIDDEN` | Insufficient permissions |
| 404 | `RESOURCE_NOT_FOUND` | Resource does not exist |
| 409 | `CONFLICT` | Invalid state for the operation |
| 422 | `BUSINESS_RULE_VIOLATION` | Business rule violated |
| 429 | `RATE_LIMIT_EXCEEDED` | Too many requests |
| 500 | `INTERNAL_ERROR` | Unexpected server error |

---

## General Conventions

- **IDs:** all are UUIDs (`string` in the format `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`)
- **Dates:** ISO 8601 — plain dates as `YYYY-MM-DD`, timestamps as `YYYY-MM-DDTHH:mm:ssZ`
- **Monetary values:** decimal `string` (e.g. `"1500.00"`) — never `float` or `int`
- **Pagination:** `page` (0-based), `size`, `sort` (e.g. `sort=createdAt,desc`)
- **Enums:** always `UPPER_SNAKE_CASE` strings
- **Absent optional fields:** returned as `null`, not omitted
