package com.cashcontrol.api.dto.response;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.UUID;

public record CategoryBudgetResponse(UUID categoryId, BigDecimal amount, YearMonth month) {}
