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
     * Due date: same month as the closing date when dueDay &gt; closingDay, next month otherwise
     * (see {@link #dueDateFor}).
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
        LocalDate dueDate = dueDateFor(closingDate, closingDay, dueDay);
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
     * O vencimento é o primeiro {@code dueDay} depois do fechamento: no mesmo mês quando
     * {@code dueDay > closingDay} (fecha 01/11, vence 10/11), no mês seguinte caso contrário
     * (fecha 25/10, vence 05/11).
     */
    public LocalDate dueDateFor(LocalDate closingDate, int closingDay, int dueDay) {
        YearMonth dueMonth = YearMonth.from(closingDate).plusMonths(dueInSameMonth(closingDay, dueDay) ? 0 : 1);
        return dueMonth.atDay(Math.min(dueDay, dueMonth.lengthOfMonth()));
    }

    /** O mês de referência (o do fechamento) da fatura que vence em {@code dueMonth}. */
    public YearMonth invoiceMonthForDueMonth(YearMonth dueMonth, int closingDay, int dueDay) {
        return dueInSameMonth(closingDay, dueDay) ? dueMonth : dueMonth.minusMonths(1);
    }

    private static boolean dueInSameMonth(int closingDay, int dueDay) {
        return dueDay > closingDay;
    }

    public record InvoiceCycleInfo(String referenceMonth, LocalDate closingDate, LocalDate dueDate) {}
}
