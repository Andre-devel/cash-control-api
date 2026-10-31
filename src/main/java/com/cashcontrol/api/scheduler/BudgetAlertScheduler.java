package com.cashcontrol.api.scheduler;

/**
 * @author Andre
 * 31 de Outubro. de 2026
 */

import com.cashcontrol.api.service.budget.BudgetAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;

/** Confere os orçamentos de quem ligou o alerta; um usuário com erro não para os outros. */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class BudgetAlertScheduler {

    private final BudgetAlertService budgetAlertService;

    @Scheduled(cron = "0 7/15 * * * *")
    public void checkBudgets() {
        LocalDate today = LocalDate.now();
        int sent = 0;
        for (UUID userId : budgetAlertService.usersToCheck()) {
            try {
                sent += budgetAlertService.check(userId, today);
            } catch (RuntimeException e) {
                log.warn("Budget alert check failed for user {}", userId, e);
            }
        }
        if (sent > 0) {
            log.info("Budget alerts sent: {}", sent);
        }
    }
}
