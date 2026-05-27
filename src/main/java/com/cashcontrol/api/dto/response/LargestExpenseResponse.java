package com.cashcontrol.api.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record LargestExpenseResponse(
        UUID id,
        BigDecimal amount,
        String description,
        String categoryName,
        String accountName,
        LocalDate paymentDate
) {}
