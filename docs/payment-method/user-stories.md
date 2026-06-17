# User Stories — Payment Method (Backend)

## Overview

This document captures user stories for the payment method feature of the Cash Control
API. The feature adds a `paymentMethod` field to transactions and installment series,
with conditional credit card linkage when the method is `CREDIT_CARD`.

All stories are scoped to the authenticated user and aligned with the existing layered
architecture (Controller → Service → Repository → Domain).

---

## US-PM-1: Set Payment Method on Transaction Creation

**As an** authenticated user  
**I want to** specify the payment method when recording a new transaction  
**So that** I can track how each payment was made and filter my history by payment channel

**Acceptance Criteria:**
- [ ] `CreateTransactionRequest` accepts an optional `paymentMethod` field with a valid slug
      (`CASH`, `PIX`, `DEBIT_CARD`, `CREDIT_CARD`, `BANK_TRANSFER`, `BOLETO`, `OTHER`).
- [ ] If `paymentMethod` is absent, the transaction is created with `paymentMethod = OTHER`.
- [ ] When `paymentMethod = CREDIT_CARD`, `creditCardId` is required; HTTP 400 if absent.
- [ ] When `paymentMethod ≠ CREDIT_CARD`, `creditCardId` must be null or absent; HTTP 422 if provided.
- [ ] `creditCardId`, when provided, must belong to the authenticated user; HTTP 403 if not.
- [ ] An archived or deleted credit card is rejected with HTTP 422.
- [ ] The created transaction response includes `paymentMethod` and, conditionally, `creditCard`.

**Expected Result:** The transaction is created with the correct payment method and optional card
reference. The conditional rule is enforced at both the validation and service layers.

---

## US-PM-2: Edit Payment Method on an Existing Transaction

**As an** authenticated user  
**I want to** update the payment method of a previously recorded transaction  
**So that** I can correct an error in how the payment was classified

**Acceptance Criteria:**
- [ ] `EditTransactionRequest` accepts optional `paymentMethod` and `creditCardId` fields.
- [ ] Changing to `CREDIT_CARD` requires `creditCardId` in the same request; HTTP 400 if absent.
- [ ] Changing from `CREDIT_CARD` to any other method nullifies `credit_card_id` on the record.
- [ ] If `paymentMethod` is not provided in the edit request, the existing value is preserved.
- [ ] All ownership and archival validation rules from US-PM-1 apply equally on edit.
- [ ] Returns the updated transaction with the new `paymentMethod` and `creditCard` values.

**Expected Result:** The payment method is corrected. The `creditCardId` is set or cleared
consistently with the new method, without affecting other transaction fields.

---

## US-PM-3: Set Payment Method on Installment Series Creation

**As an** authenticated user  
**I want to** specify the payment method when creating a new installment series  
**So that** all generated installments are correctly classified by payment channel

**Acceptance Criteria:**
- [ ] `CreateInstallmentRequest` accepts an optional `paymentMethod` field; defaults to `OTHER`.
- [ ] When `paymentMethod = CREDIT_CARD`, `creditCardId` is required at the series level; HTTP 400 if absent.
- [ ] All generated installment transactions inherit `paymentMethod` and `creditCardId` from the series.
- [ ] The `installment_series` record stores `payment_method_id` and the existing `credit_card_id`.
- [ ] Ownership and archival validation rules apply as in US-PM-1.

**Expected Result:** The series and every generated installment carry the correct payment method.
Credit card charges are correctly attributed to the selected card.

---

## US-PM-4: Edit Payment Method on Installment Series

**As an** authenticated user  
**I want to** update the payment method of an installment series  
**So that** all remaining unpaid installments are reclassified to the correct payment channel

**Acceptance Criteria:**
- [ ] `EditSeriesRequest` accepts optional `paymentMethod` and `creditCardId` fields.
- [ ] Only future `PENDING` or `OVERDUE` installments are updated; `PAID` and `CANCELLED` installments are not modified.
- [ ] Changing to `CREDIT_CARD` requires `creditCardId`; changing away from `CREDIT_CARD` nullifies `creditCardId` on all affected installments.
- [ ] The series master record is updated.
- [ ] Returns the updated series and the count of affected installments.

**Expected Result:** Future installments reflect the new payment method. Historical paid installments are preserved as-is.

---

## US-PM-5: Filter Transactions by Payment Method

**As an** authenticated user  
**I want to** filter my transaction list by payment method  
**So that** I can see all cash payments, card charges, PIX transfers, or other channels separately

**Acceptance Criteria:**
- [ ] `GET /api/v1/transactions` accepts an optional `paymentMethod` query parameter.
- [ ] When provided, only transactions with the matching `paymentMethod` slug are returned.
- [ ] The filter is applied alongside all existing filters (account, type, status, date range, etc.).
- [ ] An invalid `paymentMethod` slug returns HTTP 400 with a clear validation message.
- [ ] No cross-user data leaks: filter is always AND-combined with `user_id = :currentUserId`.

**Expected Result:** The transaction list is correctly narrowed to the selected payment channel.

---

## US-PM-6: Payment Method Included in Transaction Responses

**As an** API consumer (frontend)  
**I want to** receive payment method data in all transaction responses  
**So that** I can display the payment channel and credit card reference without additional requests

**Acceptance Criteria:**
- [ ] `TransactionSummaryResponse` (list view) includes `paymentMethod: { id, slug, name }`.
- [ ] `TransactionDetailResponse` (single view) includes `paymentMethod: { id, slug, name }` and `creditCard: { id, name, brand } | null`.
- [ ] `creditCard` is always `null` when `paymentMethod ≠ CREDIT_CARD`.
- [ ] Payment method name is returned in the API's configured locale (no i18n logic on the backend; the frontend translates slugs).

**Expected Result:** The frontend receives all payment method context in a single response,
eliminating any need for secondary requests.

---

## US-PM-7: Payment Method Lookup Endpoint

**As an** API consumer (frontend)  
**I want to** retrieve the list of available payment methods  
**So that** I can populate a payment method selector from a backend-authoritative source

**Acceptance Criteria:**
- [ ] `GET /api/v1/payment-methods` returns the list of active payment method records.
- [ ] Each entry includes: `id`, `slug`, `name`.
- [ ] The endpoint is public or requires only authentication (no special role required).
- [ ] The response is ordered consistently (e.g., by slug alphabetically or by a fixed display order).
- [ ] Inactive payment methods (if ever deactivated) are excluded from the response.

**Expected Result:** The frontend always retrieves a current, authoritative list of payment
methods from the backend rather than hardcoding slugs on the client.