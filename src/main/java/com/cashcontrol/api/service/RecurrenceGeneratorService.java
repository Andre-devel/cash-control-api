package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.RecurrenceFrequency;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class RecurrenceGeneratorService {

    public LocalDate nextOccurrence(LocalDate base, RecurrenceFrequency frequency) {
        return switch (frequency) {
            case DAILY    -> base.plusDays(1);
            case WEEKLY   -> base.plusWeeks(1);
            case BIWEEKLY -> base.plusWeeks(2);
            case MONTHLY  -> base.plusMonths(1);
            case YEARLY   -> base.plusYears(1);
        };
    }
}
