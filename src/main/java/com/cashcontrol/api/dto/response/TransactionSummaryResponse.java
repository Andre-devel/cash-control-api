package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Transaction summary entry returned in paginated list results")
public record TransactionSummaryResponse(
        @Schema(description = "Transaction UUID") UUID id,
        @Schema(description = "Account UUID") UUID accountId,
        @Schema(description = "Account name") String accountName,
        @Schema(description = "Transaction type") TransactionType type,
        @Schema(description = "Current payment status") TransactionStatus status,
        @Schema(description = "Transaction amount. Serialized as decimal string.", example = "150.00") BigDecimal amount,
        @Schema(description = "Short transaction label", example = "Supermarket purchase") String description,
        @Schema(description = "Accrual date — when the financial event occurred", example = "2026-05-15") LocalDate competenceDate,
        @Schema(description = "Settlement date — when payment was actually made. Null for PENDING transactions.", example = "2026-05-15") LocalDate paymentDate,
        @Schema(description = "Primary category UUID") UUID categoryId,
        @Schema(description = "Primary category name") String categoryName,
        @Schema(description = "Record creation timestamp") Instant createdAt,
        @Schema(description = "Payment method used for this transaction") PaymentMethodResponse paymentMethod,
        @Schema(description = "Parent installment series UUID. Null for non-installment transactions.") UUID installmentSeriesId,
        @Schema(description = "1-based installment position within the series. Null for non-installment transactions.", example = "2") Integer installmentNumber,
        @Schema(description = "Total installments in the series. Null for non-installment transactions.", example = "5") Integer totalInstallments,
        @Schema(description = "Sum of the non-cancelled installments of the series. Only filled on grouped rows.", example = "1000.00") BigDecimal installmentTotalAmount,
        @Schema(description = "How many installments of the series are already PAID. Only filled on grouped rows.", example = "1") Integer paidInstallments,
        @Schema(description = "True when this row stands for the whole series (groupInstallments=true) instead of a single installment.") boolean installmentGroup,
        @Schema(description = "Reimbursement link. Null when the transaction is not part of one.") ReimbursementResponse reimbursement,
        @Schema(description = "Who received (outflow) or paid (inflow), as reported by the bank. Null when not imported from the bank.", example = "Fulano de Tal") String counterpartyName,
        @Schema(description = "Counterparty CPF/CNPJ, masked as the bank sent it.", example = "***.123.456-**") String counterpartyDocument,
        @Schema(description = "True on a transfer between accounts of different scopes (personal and business): "
                              + "owner's draw or capital contribution. Never counts as income or expense.")
        boolean crossScope
) {

    /** Non-installment row, or an installment listed individually (groupInstallments=false). */
    public static TransactionSummaryResponse ungrouped(
            UUID id, UUID accountId, String accountName, TransactionType type, TransactionStatus status,
            BigDecimal amount, String description, LocalDate competenceDate, LocalDate paymentDate,
            UUID categoryId, String categoryName, Instant createdAt, PaymentMethodResponse paymentMethod,
            UUID installmentSeriesId, Integer installmentNumber, Integer totalInstallments,
            String counterpartyName, String counterpartyDocument) {
        return new TransactionSummaryResponse(
                id, accountId, accountName, type, status, amount, description,
                competenceDate, paymentDate, categoryId, categoryName, createdAt, paymentMethod,
                installmentSeriesId, installmentNumber, totalInstallments, null, null, false, null,
                counterpartyName, counterpartyDocument, false);
    }

    public TransactionSummaryResponse withReimbursement(ReimbursementResponse value) {
        return new TransactionSummaryResponse(
                id, accountId, accountName, type, status, amount, description,
                competenceDate, paymentDate, categoryId, categoryName, createdAt, paymentMethod,
                installmentSeriesId, installmentNumber, totalInstallments, installmentTotalAmount,
                paidInstallments, installmentGroup, value, counterpartyName, counterpartyDocument, crossScope);
    }

    public TransactionSummaryResponse withCrossScope(boolean value) {
        return new TransactionSummaryResponse(
                id, accountId, accountName, type, status, amount, description,
                competenceDate, paymentDate, categoryId, categoryName, createdAt, paymentMethod,
                installmentSeriesId, installmentNumber, totalInstallments, installmentTotalAmount,
                paidInstallments, installmentGroup, reimbursement, counterpartyName, counterpartyDocument, value);
    }
}
