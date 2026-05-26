package com.cashcontrol.api.dto.request;

import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record TransactionFilterRequest(
        UUID accountId,
        TransactionType type,
        TransactionStatus status,
        UUID categoryId,
        LocalDate competenceDateFrom,
        LocalDate competenceDateTo,
        LocalDate paymentDateFrom,
        LocalDate paymentDateTo,
        BigDecimal amountMin,
        BigDecimal amountMax,
        String searchText,
        boolean includeCancelled
) {
    public static TransactionFilterRequest empty() {
        return new TransactionFilterRequest(null, null, null, null,
                null, null, null, null, null, null, null, false);
    }
}
