package com.cashcontrol.api.scheduler;

import com.cashcontrol.api.service.InvoiceManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Avança as faturas não pagas para FECHADA e VENCIDA conforme as datas passam. */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class InvoiceStatusScheduler {

    private final InvoiceManagementService invoiceManagementService;

    @Scheduled(cron = "0 30 0 * * *")
    public void refreshInvoiceStatuses() {
        int count = invoiceManagementService.refreshInvoiceStatuses();
        log.info("Invoice status refresh complete: transitioned={}", count);
    }
}
