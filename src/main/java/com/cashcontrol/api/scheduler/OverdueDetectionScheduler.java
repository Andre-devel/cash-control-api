package com.cashcontrol.api.scheduler;

import com.cashcontrol.api.service.TransactionService;
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
public class OverdueDetectionScheduler {

    private final TransactionService transactionService;

    @Scheduled(cron = "0 0 1 * * *")
    @Transactional
    public void detectOverdue() {
        int count = transactionService.detectOverdueAll();
        log.info("Overdue detection complete: transitioned={}", count);
    }
}
