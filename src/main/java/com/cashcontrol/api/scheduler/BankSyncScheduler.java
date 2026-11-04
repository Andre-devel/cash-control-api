package com.cashcontrol.api.scheduler;

/**
 * @author Andre
 * 31 de Outubro. de 2026
 */

import com.cashcontrol.api.service.openfinance.BankConnectionAlertService;
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
 * para as outras. Uma vez por dia apaga o histórico de sincronizações que passou da retenção e
 * avisa por push as conexões que precisam de ação (erro, sem dados novos, consentimento vencendo).
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
    private final BankConnectionAlertService bankConnectionAlertService;

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

    /**
     * Às 10:23 de Brasília a sincronização diária de cada item já passou há algumas horas; se uma
     * falhar, no dia seguinte a conexão passa das 26 h sem dados e o aviso sai.
     */
    @Scheduled(cron = "0 23 10 * * *", zone = "America/Sao_Paulo")
    public void alertConnections() {
        int sent = bankConnectionAlertService.checkAll(Instant.now());
        if (sent > 0) {
            log.info("Bank connection alerts sent: {}", sent);
        }
    }

    @Scheduled(cron = "0 41 3 * * *")
    public void purgeRuns() {
        bankSyncRunService.purge(Instant.now());
    }
}
