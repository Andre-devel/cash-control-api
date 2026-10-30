package com.cashcontrol.api.scheduler;

import com.cashcontrol.api.service.marketdata.MarketDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Duas vezes por dia, no horário de Brasília: o CDI de um dia útil sai na manhã do seguinte e o
 * CSV do Tesouro é republicado por volta das 10h. A execução da noite cobre o que atrasou.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class MarketDataSyncScheduler {

    private final MarketDataService marketDataService;

    @Scheduled(cron = "0 17 10,19 * * *", zone = "America/Sao_Paulo")
    public void sync() {
        marketDataService.sync(LocalDate.now(MarketDataService.MARKET_ZONE));
    }

    /** Banco novo: sem esperar o próximo horário, senão a tela de investimentos fica sem índices até lá. */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartupIfEmpty() {
        if (marketDataService.isEmpty()) {
            sync();
        }
    }
}
