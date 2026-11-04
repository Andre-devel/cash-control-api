package com.cashcontrol.api.scheduler;

/**
 * @author Andre
 * 31 de Outubro. de 2026
 */

import com.cashcontrol.api.service.openfinance.BankConnectionService;
import com.cashcontrol.api.service.openfinance.BankSyncRunService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Relê de hora em hora os itens da Pluggy e põe na fila o que chegou desde a última leitura —
 * a Pluggy não chama o webhook quando o item do MeuPluggy sincroniza. Uma conexão com erro não
 * para as outras. Uma vez por dia apaga o histórico de sincronizações que passou da retenção.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class BankSyncScheduler {

    /** Minuto 13 de toda hora; a tela de sincronizações calcula a próxima a partir daqui. */
    public static final String POLL_CRON = "0 13 * * * *";

    private final BankConnectionService bankConnectionService;
    private final BankSyncRunService bankSyncRunService;

    @Scheduled(cron = POLL_CRON)
    public void pollConnections() {
        for (UUID connectionId : bankConnectionService.pluggyConnectionIds()) {
            try {
                bankConnectionService.poll(connectionId);
            } catch (RuntimeException e) {
                log.warn("Bank sync failed for connection {}: {}", connectionId, e.toString());
            }
        }
    }

    @Scheduled(cron = "0 41 3 * * *")
    public void purgeRuns() {
        bankSyncRunService.purge(Instant.now());
    }
}
