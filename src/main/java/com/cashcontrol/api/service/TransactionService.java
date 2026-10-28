package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.CreditCard;
import com.cashcontrol.api.domain.entity.PaymentMethod;
import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.dto.request.CreateTransactionRequest;
import com.cashcontrol.api.dto.request.EditTransactionRequest;
import com.cashcontrol.api.dto.request.MarkAsPaidRequest;
import com.cashcontrol.api.dto.request.TransactionFilterRequest;
import com.cashcontrol.api.dto.response.TransactionDetailResponse;
import com.cashcontrol.api.dto.response.TransactionSummaryResponse;
import com.cashcontrol.api.dto.response.TransactionTotalsResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface TransactionService {

    /**
     * Compra no cartão sai da conta pelo pagamento da fatura, que é quem a marca como PAGA.
     * Pagá-la à parte debitaria a conta duas vezes.
     */
    String CARD_PURCHASE_PAID_BY_INVOICE =
            "Compras no cartão de crédito são quitadas pelo pagamento da fatura, não individualmente.";

    TransactionDetailResponse createTransaction(CreateTransactionRequest request, UUID userId);

    TransactionDetailResponse editTransaction(UUID id, EditTransactionRequest request, UUID userId);

    void deleteTransaction(UUID id, UUID userId);

    TransactionDetailResponse markAsPaid(UUID id, MarkAsPaidRequest request, UUID userId);

    TransactionDetailResponse cancelTransaction(UUID id, UUID userId);

    /** Desfaz o cancelamento: volta para PENDING. Parcelas não podem ser reativadas sozinhas. */
    TransactionDetailResponse reactivateTransaction(UUID id, UUID userId);

    Page<TransactionSummaryResponse> listTransactions(TransactionFilterRequest filter, UUID userId, Pageable pageable);

    /** Totais por tipo de tudo que o filtro casa (todas as páginas). */
    TransactionTotalsResponse summarizeTransactions(TransactionFilterRequest filter, UUID userId);

    TransactionDetailResponse getTransaction(UUID id, UUID userId);

    int detectOverdue(UUID userId);

    int detectOverdueAll();

    PaymentMethod resolvePaymentMethod(PaymentMethodSlug slug);

    CreditCard validateAndResolveCreditCard(UUID creditCardId, PaymentMethodSlug slug, UUID userId);

    TransactionDetailResponse toDetail(Transaction tx);

    TransactionSummaryResponse toSummary(Transaction tx);
}
