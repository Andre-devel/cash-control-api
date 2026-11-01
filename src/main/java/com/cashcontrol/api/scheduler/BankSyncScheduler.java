package com.cashcontrol.api.scheduler;

/**
 * @author Andre
 * 31 de Outubro. de 2026
 */

import com.cashcontrol.api.service.openfinance.BankConnectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Relê de hora em hora os itens da Pluggy e põe na fila o que chegou desde a última leitura —
 * a Pluggy não chama o webhook quando o item do MeuPluggy sincroniza. Uma conexão com erro não
 * para as outras.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class BankSyncScheduler {

    private final BankConnectionService bankConnectionService;

    @Scheduled(cron = "0 13 * * * *")
    public void pollConnections() {
        for (UUID connectionId : bankConnectionService.pluggyConnectionIds()) {
            try {
                bankConnectionService.poll(connectionId);
            } catch (RuntimeException e) {
                log.warn("Bank sync failed for connection {}: {}", connectionId, e.toString());
            }
        }
    }
}
