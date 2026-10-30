package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "Projected balance of the active accounts over the next days, from what is already recorded")
public record BalanceProjectionResponse(
        @Schema(description = "First projected day (today)") LocalDate from,
        @Schema(description = "Last projected day") LocalDate to,
        @Schema(description = "Current balance, before any pending item", example = "2500.00") BigDecimal startBalance,
        @Schema(description = "Projected balance on the last day", example = "1800.00") BigDecimal endBalance,
        @Schema(description = "Lowest projected balance in the period") Point lowest,
        @Schema(description = "First day the projected balance goes below zero. Null when it never does.") LocalDate firstNegativeDate,
        @Schema(description = "Balance after each day with events, plus today and the last day, in date order") List<Point> points,
        @Schema(description = "Every item moving the balance, in date order") List<Event> events
) {

    public record Point(LocalDate date, BigDecimal balance) {}

    public record Event(
            @Schema(description = "Expected date; overdue items are moved to today") LocalDate date,
            EventKind kind,
            @Schema(example = "Aluguel") String description,
            @Schema(description = "Signed effect on the balance: positive comes in, negative goes out", example = "-1500.00") BigDecimal amount,
            @Schema(description = "True when the item does not exist yet and was derived from a recurrence rule") boolean estimated
    ) {}

    public enum EventKind {
        /** Receita ou despesa pendente já lançada (inclui ocorrências e parcelas geradas). */
        TRANSACTION,
        /** Restante a pagar de uma fatura de cartão. */
        INVOICE,
        /** Ocorrência futura de uma recorrência, ainda não gerada. */
        RECURRENCE
    }
}
