package com.cashcontrol.api.scheduler;

import com.cashcontrol.api.service.RecurrenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class RecurrenceGenerationScheduler {

    static final int LOOKAHEAD_DAYS = 30;

    private final RecurrenceService recurrenceService;

    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void generateRecurrences() {
        int count = recurrenceService.generatePendingInstances(LOOKAHEAD_DAYS);
        log.info("Recurrence generation complete: generated={}", count);
    }
}
