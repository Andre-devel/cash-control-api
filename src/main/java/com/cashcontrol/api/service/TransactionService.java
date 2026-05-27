package com.cashcontrol.api.service;

import com.cashcontrol.api.dto.request.CreateTransactionRequest;
import com.cashcontrol.api.dto.request.EditTransactionRequest;
import com.cashcontrol.api.dto.request.MarkAsPaidRequest;
import com.cashcontrol.api.dto.request.TransactionFilterRequest;
import com.cashcontrol.api.dto.response.TransactionDetailResponse;
import com.cashcontrol.api.dto.response.TransactionSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface TransactionService {

    TransactionDetailResponse createTransaction(CreateTransactionRequest request, UUID userId);

    TransactionDetailResponse editTransaction(UUID id, EditTransactionRequest request, UUID userId);

    void deleteTransaction(UUID id, UUID userId);

    TransactionDetailResponse markAsPaid(UUID id, MarkAsPaidRequest request, UUID userId);

    TransactionDetailResponse cancelTransaction(UUID id, UUID userId);

    Page<TransactionSummaryResponse> listTransactions(TransactionFilterRequest filter, UUID userId, Pageable pageable);

    TransactionDetailResponse getTransaction(UUID id, UUID userId);

    int detectOverdue(UUID userId);

    int detectOverdueAll();
}
