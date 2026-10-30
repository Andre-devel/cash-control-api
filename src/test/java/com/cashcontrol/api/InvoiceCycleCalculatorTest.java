package com.cashcontrol.api;

import com.cashcontrol.api.service.InvoiceCycleCalculator;
import com.cashcontrol.api.service.InvoiceCycleCalculator.InvoiceCycleInfo;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceCycleCalculatorTest {

    private final InvoiceCycleCalculator calculator = new InvoiceCycleCalculator();

    // closingDay=15, dueDay=10

    @Test
    void chargeOnDay10_withClosingDay15_belongsToCurrentMonth() {
        LocalDate competenceDate = LocalDate.of(2025, 5, 10);
        InvoiceCycleInfo info = calculator.calculateForCharge(competenceDate, 15, 10);

        assertThat(info.referenceMonth()).isEqualTo("2025-05");
        assertThat(info.closingDate()).isEqualTo(LocalDate.of(2025, 5, 15));
        assertThat(info.dueDate()).isEqualTo(LocalDate.of(2025, 6, 10));
    }

    @Test
    void chargeOnDay15_withClosingDay15_belongsToCurrentMonth_inclusive() {
        LocalDate competenceDate = LocalDate.of(2025, 5, 15);
        InvoiceCycleInfo info = calculator.calculateForCharge(competenceDate, 15, 10);

        assertThat(info.referenceMonth()).isEqualTo("2025-05");
        assertThat(info.closingDate()).isEqualTo(LocalDate.of(2025, 5, 15));
        assertThat(info.dueDate()).isEqualTo(LocalDate.of(2025, 6, 10));
    }

    @Test
    void chargeOnDay20_withClosingDay15_belongsToNextMonth() {
        LocalDate competenceDate = LocalDate.of(2025, 5, 20);
        InvoiceCycleInfo info = calculator.calculateForCharge(competenceDate, 15, 10);

        assertThat(info.referenceMonth()).isEqualTo("2025-06");
        assertThat(info.closingDate()).isEqualTo(LocalDate.of(2025, 6, 15));
        assertThat(info.dueDate()).isEqualTo(LocalDate.of(2025, 7, 10));
    }

    @Test
    void chargeInDecemberAfterClosingDay_goesToJanuaryInvoice() {
        // December 20 with closingDay=15 → next month is January
        LocalDate competenceDate = LocalDate.of(2025, 12, 20);
        InvoiceCycleInfo info = calculator.calculateForCharge(competenceDate, 15, 10);

        assertThat(info.referenceMonth()).isEqualTo("2026-01");
        assertThat(info.closingDate()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(info.dueDate()).isEqualTo(LocalDate.of(2026, 2, 10));
    }

    @Test
    void closingDayClamped_toLastDayOfFebruary() {
        // closingDay=31 in February → clamped to 28
        LocalDate competenceDate = LocalDate.of(2025, 2, 1);
        InvoiceCycleInfo info = calculator.calculateForCharge(competenceDate, 31, 10);

        assertThat(info.referenceMonth()).isEqualTo("2025-02");
        // Feb 2025 has 28 days
        assertThat(info.closingDate()).isEqualTo(LocalDate.of(2025, 2, 28));
        assertThat(info.dueDate()).isEqualTo(LocalDate.of(2025, 3, 10));
    }

    @Test
    void closingDayClamped_toLastDayOfLeapYearFebruary() {
        // 2024 is a leap year — Feb has 29 days
        LocalDate competenceDate = LocalDate.of(2024, 2, 1);
        InvoiceCycleInfo info = calculator.calculateForCharge(competenceDate, 31, 10);

        assertThat(info.referenceMonth()).isEqualTo("2024-02");
        assertThat(info.closingDate()).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(info.dueDate()).isEqualTo(LocalDate.of(2024, 3, 10));
    }

    @Test
    void dueDayAfterClosingDay_dueInSameMonth() {
        // fecha dia 1, vence dia 10: a fatura que fecha em 01/11 vence em 10/11
        InvoiceCycleInfo info = calculator.calculateForCharge(LocalDate.of(2026, 10, 27), 1, 10);

        assertThat(info.referenceMonth()).isEqualTo("2026-11");
        assertThat(info.closingDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(info.dueDate()).isEqualTo(LocalDate.of(2026, 11, 10));
    }

    @Test
    void dueDayEqualToClosingDay_dueInNextMonth() {
        InvoiceCycleInfo info = calculator.calculateForCharge(LocalDate.of(2026, 10, 5), 10, 10);

        assertThat(info.closingDate()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(info.dueDate()).isEqualTo(LocalDate.of(2026, 11, 10));
    }

    @Test
    void invoiceMonthForDueMonth_followsTheCardCycle() {
        assertThat(calculator.invoiceMonthForDueMonth(YearMonth.of(2026, 11), 1, 10)).isEqualTo(YearMonth.of(2026, 11));
        assertThat(calculator.invoiceMonthForDueMonth(YearMonth.of(2026, 11), 25, 5)).isEqualTo(YearMonth.of(2026, 10));
    }

    @Test
    void competenceInCycle_landsInTheRequestedInvoice() {
        YearMonth october = YearMonth.of(2026, 10);

        // Até o fechamento, no próprio mês; depois dele, no mês anterior.
        assertThat(calculator.competenceInCycle(24, october, 30)).isEqualTo(LocalDate.of(2026, 10, 24));
        assertThat(calculator.competenceInCycle(31, YearMonth.of(2026, 11), 30)).isEqualTo(LocalDate.of(2026, 10, 31));
        // Setembro não tem dia 31: vai para o dia 1 da própria fatura, que nunca passa do fechamento.
        assertThat(calculator.competenceInCycle(31, october, 30)).isEqualTo(LocalDate.of(2026, 10, 1));
        // Fechamento no 30 em fevereiro cai no último dia do mês.
        assertThat(calculator.competenceInCycle(30, YearMonth.of(2026, 2), 30)).isEqualTo(LocalDate.of(2026, 2, 28));
        for (LocalDate competence : new LocalDate[] {
                calculator.competenceInCycle(31, october, 30),
                calculator.competenceInCycle(30, YearMonth.of(2026, 2), 30)}) {
            assertThat(calculator.calculateForCharge(competence, 30, 7).referenceMonth())
                    .isEqualTo(calculator.toReferenceMonth(YearMonth.from(competence)));
        }
    }

    @Test
    void referenceMonthFormat_isYearDashMonth() {
        LocalDate competenceDate = LocalDate.of(2025, 1, 5);
        InvoiceCycleInfo info = calculator.calculateForCharge(competenceDate, 15, 10);

        assertThat(info.referenceMonth()).matches("\\d{4}-\\d{2}");
        assertThat(info.referenceMonth()).isEqualTo("2025-01");
    }
}
