package com.cashcontrol.api;

import com.cashcontrol.api.domain.entity.RecurrenceFrequency;
import com.cashcontrol.api.service.RecurrenceGeneratorService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class RecurrenceGeneratorServiceTest {

    private final RecurrenceGeneratorService generator = new RecurrenceGeneratorService();

    @Test
    void daily_advancesByOneDay() {
        LocalDate base = LocalDate.of(2026, 1, 15);
        assertThat(generator.nextOccurrence(base, RecurrenceFrequency.DAILY))
                .isEqualTo(LocalDate.of(2026, 1, 16));
    }

    @Test
    void daily_crossesMonthBoundary() {
        LocalDate base = LocalDate.of(2026, 1, 31);
        assertThat(generator.nextOccurrence(base, RecurrenceFrequency.DAILY))
                .isEqualTo(LocalDate.of(2026, 2, 1));
    }

    @Test
    void weekly_advancesBySevenDays() {
        LocalDate base = LocalDate.of(2026, 6, 1);
        assertThat(generator.nextOccurrence(base, RecurrenceFrequency.WEEKLY))
                .isEqualTo(LocalDate.of(2026, 6, 8));
    }

    @Test
    void biweekly_advancesByFourteenDays() {
        LocalDate base = LocalDate.of(2026, 6, 1);
        assertThat(generator.nextOccurrence(base, RecurrenceFrequency.BIWEEKLY))
                .isEqualTo(LocalDate.of(2026, 6, 15));
    }

    @Test
    void monthly_sameDay() {
        LocalDate base = LocalDate.of(2026, 1, 15);
        assertThat(generator.nextOccurrence(base, RecurrenceFrequency.MONTHLY))
                .isEqualTo(LocalDate.of(2026, 2, 15));
    }

    @Test
    void monthly_jan31_clampsToFeb28() {
        LocalDate base = LocalDate.of(2026, 1, 31);
        assertThat(generator.nextOccurrence(base, RecurrenceFrequency.MONTHLY))
                .isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void monthly_jan31_leapYear_clampsToFeb29() {
        LocalDate base = LocalDate.of(2028, 1, 31);
        assertThat(generator.nextOccurrence(base, RecurrenceFrequency.MONTHLY))
                .isEqualTo(LocalDate.of(2028, 2, 29));
    }

    @Test
    void monthly_mar31_clampsToApr30() {
        LocalDate base = LocalDate.of(2026, 3, 31);
        assertThat(generator.nextOccurrence(base, RecurrenceFrequency.MONTHLY))
                .isEqualTo(LocalDate.of(2026, 4, 30));
    }

    @Test
    void monthly_crossesYearBoundary() {
        LocalDate base = LocalDate.of(2026, 12, 15);
        assertThat(generator.nextOccurrence(base, RecurrenceFrequency.MONTHLY))
                .isEqualTo(LocalDate.of(2027, 1, 15));
    }

    @Test
    void yearly_sameDate() {
        LocalDate base = LocalDate.of(2026, 6, 15);
        assertThat(generator.nextOccurrence(base, RecurrenceFrequency.YEARLY))
                .isEqualTo(LocalDate.of(2027, 6, 15));
    }

    @Test
    void yearly_feb29_leapToNonLeap_clampsToFeb28() {
        LocalDate base = LocalDate.of(2028, 2, 29);
        assertThat(generator.nextOccurrence(base, RecurrenceFrequency.YEARLY))
                .isEqualTo(LocalDate.of(2029, 2, 28));
    }

    @Test
    void yearly_feb29_leapToLeap_staysFeb29() {
        LocalDate base = LocalDate.of(2024, 2, 29);
        assertThat(generator.nextOccurrence(base, RecurrenceFrequency.YEARLY))
                .isEqualTo(LocalDate.of(2025, 2, 28));
    }
}
