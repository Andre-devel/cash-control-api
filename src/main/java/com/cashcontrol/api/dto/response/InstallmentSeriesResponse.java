package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record InstallmentSeriesResponse(
        UUID id,
        UUID accountId,
        String accountName,
        TransactionType type,
        String description,
        /** Soma das parcelas não canceladas (mais a quitação, se houver): reflete edições e antecipações. */
        BigDecimal totalAmount,
        int totalInstallments,
        int paidInstallments,
        BigDecimal remainingAmount,
        /** Competência da primeira parcela em aberto; nulo quando não resta nenhuma. */
        LocalDate nextDueDate,
        /** Todas as parcelas pagas, sem quitação antecipada. */
        boolean completed,
        LocalDate firstPaymentDate,
        UUID categoryId,
        String categoryName,
        boolean settled,
        Instant settledAt,
        Instant createdAt,
        Instant updatedAt
) {}
