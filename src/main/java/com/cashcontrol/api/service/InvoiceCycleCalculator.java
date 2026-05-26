package com.cashcontrol.api.service;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

@Component
public class InvoiceCycleCalculator {

    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * Given a competence date and the card's closing day, determines:
     * - the referenceMonth (YYYY-MM) of the invoice this charge belongs to
     * - the closingDate of that invoice
     * - the dueDate based on dueDay
     *
     * Logic:
     * - If competenceDate.dayOfMonth &lt;= closingDay: charge belongs to current month's invoice
     * - If competenceDate.dayOfMonth &gt; closingDay: charge belongs to next month's invoice
     *
     * Due date is always in the month AFTER the closing date month.
     */
    public InvoiceCycleInfo calculateForCharge(LocalDate competenceDate, int closingDay, int dueDay) {
        YearMonth chargeMonth = YearMonth.from(competenceDate);
        YearMonth invoiceMonth;

        if (competenceDate.getDayOfMonth() <= closingDay) {
            invoiceMonth = chargeMonth;
        } else {
            invoiceMonth = chargeMonth.plusMonths(1);
        }

        LocalDate closingDate = closingDateFor(invoiceMonth, closingDay);
        LocalDate dueDate = dueDateFor(closingDate, dueDay);
        String referenceMonth = toReferenceMonth(invoiceMonth);

        return new InvoiceCycleInfo(referenceMonth, closingDate, dueDate);
    }

    /**
     * Computes the referenceMonth string "YYYY-MM" for a given YearMonth.
     */
    public String toReferenceMonth(YearMonth yearMonth) {
        return yearMonth.format(MONTH_FORMATTER);
    }

    /**
     * Given a base date, determines the closingDate (clamped to last day of month if needed).
     */
    public LocalDate closingDateFor(YearMonth yearMonth, int closingDay) {
        int actualDay = Math.min(closingDay, yearMonth.lengthOfMonth());
        return yearMonth.atDay(actualDay);
    }

    /**
     * Determines the dueDate for a billing cycle.
     * Due date is always in the month AFTER the closingDate month.
     */
    public LocalDate dueDateFor(LocalDate closingDate, int dueDay) {
        YearMonth nextMonth = YearMonth.from(closingDate).plusMonths(1);
        int actualDay = Math.min(dueDay, nextMonth.lengthOfMonth());
        return nextMonth.atDay(actualDay);
    }

    public record InvoiceCycleInfo(String referenceMonth, LocalDate closingDate, LocalDate dueDate) {}
}
