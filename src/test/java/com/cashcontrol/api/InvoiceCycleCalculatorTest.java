package com.cashcontrol.api;

import com.cashcontrol.api.service.InvoiceCycleCalculator;
import com.cashcontrol.api.service.InvoiceCycleCalculator.InvoiceCycleInfo;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

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
    void dueDayClamped_whenDueDay28InFebruaryDueMonth() {
        // closingDay=20, dueDay=28. If closing is Jan 20, due is in Feb
        // 2025 Feb has 28 days so dueDay=28 is exactly last day → OK
        LocalDate competenceDate = LocalDate.of(2025, 1, 10);
        InvoiceCycleInfo info = calculator.calculateForCharge(competenceDate, 20, 28);

        assertThat(info.referenceMonth()).isEqualTo("2025-01");
        assertThat(info.closingDate()).isEqualTo(LocalDate.of(2025, 1, 20));
        assertThat(info.dueDate()).isEqualTo(LocalDate.of(2025, 2, 28));
    }

    @Test
    void referenceMonthFormat_isYearDashMonth() {
        LocalDate competenceDate = LocalDate.of(2025, 1, 5);
        InvoiceCycleInfo info = calculator.calculateForCharge(competenceDate, 15, 10);

        assertThat(info.referenceMonth()).matches("\\d{4}-\\d{2}");
        assertThat(info.referenceMonth()).isEqualTo("2025-01");
    }
}
