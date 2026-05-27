package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.TransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record UpcomingBillResponse(
        UUID id,
        BigDecimal amount,
        String description,
        String accountName,
        String categoryName,
        LocalDate paymentDate,
        TransactionStatus status
) {}
