package com.cashcontrol.api.dto.response;

/**
 * @author Andre
 * 4 de Novembro. de 2026
 */

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Expense that repeats in the history and has no recurrence rule yet")
public record RecurrenceSuggestionResponse(
        @Schema(description = "Normalized description shared by the grouped expenses; key used to dismiss the suggestion")
        String pattern,
        @Schema(description = "Original description of the most recent expense of the group")
        String description,
        @Schema(description = "Median amount of the grouped expenses. Serialized as decimal string.", example = "39.90")
        BigDecimal amount,
        @Schema(description = "Day of month of the most recent expense")
        int dayOfMonth,
        @Schema(description = "Distinct months in which the expense appeared")
        int months,
        @Schema(description = "Date of the most recent expense")
        LocalDate lastDate,
        @Schema(description = "Category of the most recent expense")
        UUID categoryId,
        @Schema(description = "Account of the most recent expense")
        UUID accountId,
        @Schema(description = "Credit card of the most recent expense, when it was a card purchase")
        UUID creditCardId
) {}
