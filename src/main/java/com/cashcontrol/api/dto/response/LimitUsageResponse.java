package com.cashcontrol.api.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

public record LimitUsageResponse(
        UUID cardId,
        BigDecimal creditLimit,
        BigDecimal usedLimit,
        BigDecimal availableLimit
) {}
