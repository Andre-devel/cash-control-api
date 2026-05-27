package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record RecentTransactionResponse(
        UUID id,
        BigDecimal amount,
        String description,
        TransactionType type,
        TransactionStatus status,
        String accountName,
        String categoryName,
        LocalDate competenceDate
) {}
