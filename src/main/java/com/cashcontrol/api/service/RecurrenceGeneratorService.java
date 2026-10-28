package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.RecurrenceFrequency;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service
public class RecurrenceGeneratorService {

    /**
     * Próxima data do calendário da regra depois de {@code current}, sempre contada a partir de
     * {@code start}: uma mensal do dia 31 cai em 28/02 e volta a 31/03, em vez de ficar presa no 28.
     */
    public LocalDate nextOccurrence(LocalDate start, LocalDate current, RecurrenceFrequency frequency) {
        long n = switch (frequency) {
            case DAILY    -> ChronoUnit.DAYS.between(start, current);
            case WEEKLY   -> ChronoUnit.WEEKS.between(start, current);
            case BIWEEKLY -> ChronoUnit.WEEKS.between(start, current) / 2;
            case MONTHLY  -> ChronoUnit.MONTHS.between(start, current);
            case YEARLY   -> ChronoUnit.YEARS.between(start, current);
        };
        LocalDate candidate = occurrence(start, Math.max(n, 0), frequency);
        while (!candidate.isAfter(current)) {
            candidate = occurrence(start, ++n, frequency);
        }
        return candidate;
    }

    private LocalDate occurrence(LocalDate start, long n, RecurrenceFrequency frequency) {
        return switch (frequency) {
            case DAILY    -> start.plusDays(n);
            case WEEKLY   -> start.plusWeeks(n);
            case BIWEEKLY -> start.plusWeeks(2 * n);
            case MONTHLY  -> start.plusMonths(n);
            case YEARLY   -> start.plusYears(n);
        };
    }
}
