package com.cashcontrol.api.service.marketdata;

import com.cashcontrol.api.domain.entity.MarketIndex;
import com.cashcontrol.api.dto.response.MarketIndexesResponse;
import com.cashcontrol.api.repository.MarketDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Sincroniza CDI, Selic, IPCA (SGS/BCB) e os preços do Tesouro Direto.
 *
 * <p>Incremental e idempotente: cada fonte continua da última data gravada, e o upsert absorve
 * a sobreposição. Uma fonte fora do ar só gera WARN — as outras seguem, e a próxima execução
 * completa o que faltou. Fora de transação de propósito: a chamada HTTP não deve segurar uma
 * conexão do banco, e o que já foi gravado vale mesmo se a fonte cair no meio.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketDataService {

    /** Fuso das fontes: "hoje" e os horários do job são os do mercado brasileiro, não os do servidor. */
    public static final ZoneId MARKET_ZONE = ZoneId.of("America/Sao_Paulo");

    /** Histórico buscado na primeira sincronização de cada fonte. */
    static final int INITIAL_HISTORY_YEARS = 5;

    /**
     * Atraso normal máximo de uma fonte diária: o valor de um dia útil sai na manhã do seguinte,
     * e o pior caso é o carnaval (segunda e terça sem pregão), em que a quarta de manhã ainda
     * mostra a sexta.
     */
    static final int DAILY_STALE_AFTER_DAYS = 5;

    /**
     * O IPCA de um mês sai por volta do dia 10 do mês seguinte. Do dia 15 em diante, o mês anterior
     * já devia estar gravado.
     */
    static final int IPCA_PUBLISHED_BY_DAY = 15;

    /** Limite do SGS por consulta em série diária. */
    private static final int SGS_MAX_WINDOW_YEARS = 10;

    private final BcbSgsClient bcbSgsClient;
    private final TesouroDiretoClient tesouroDiretoClient;
    private final MarketDataRepository marketDataRepository;

    public SyncResult sync(LocalDate today) {
        LocalDate initialStart = today.minusYears(INITIAL_HISTORY_YEARS);
        int indexRates = 0;
        List<String> failedSources = new ArrayList<>();

        for (MarketIndex index : MarketIndex.values()) {
            try {
                indexRates += syncIndex(index, initialStart, today);
            } catch (RuntimeException e) {
                log.warn("Market data sync failed: source={} error={}", index, e.toString());
                failedSources.add(index.name());
            }
        }

        int treasuryPrices = 0;
        try {
            LocalDate after = marketDataRepository.findLatestTreasuryBaseDate().orElse(initialStart.minusDays(1));
            treasuryPrices = tesouroDiretoClient.fetchPrices(after, marketDataRepository::upsertTreasuryPrices);
        } catch (RuntimeException e) {
            log.warn("Market data sync failed: source=TESOURO error={}", e.toString());
            failedSources.add("TESOURO");
        }

        log.info("Market data sync complete: indexRates={} treasuryPrices={} failed={}",
                indexRates, treasuryPrices, failedSources);
        warnIfStale(today);
        return new SyncResult(indexRates, treasuryPrices, failedSources);
    }

    /** {@code true} quando alguma fonte nunca foi sincronizada (banco novo). */
    public boolean isEmpty() {
        return Arrays.stream(MarketIndex.values()).anyMatch(index -> marketDataRepository.findLatestRate(index).isEmpty())
                || marketDataRepository.findLatestTreasuryBaseDate().isEmpty();
    }

    /** Último valor de cada fonte e se ele está mais velho do que o atraso normal da fonte. */
    public MarketIndexesResponse getLatest(LocalDate today) {
        List<MarketIndexesResponse.IndexValue> indexes = Arrays.stream(MarketIndex.values())
                .map(index -> marketDataRepository.findLatestRate(index)
                        .map(rate -> new MarketIndexesResponse.IndexValue(
                                index, rate.date(), rate.value(), isStale(index, rate.date(), today)))
                        .orElseGet(() -> new MarketIndexesResponse.IndexValue(index, null, null, true)))
                .toList();
        LocalDate treasuryBaseDate = marketDataRepository.findLatestTreasuryBaseDate().orElse(null);
        return new MarketIndexesResponse(indexes, treasuryBaseDate, isDailyStale(treasuryBaseDate, today));
    }

    static boolean isStale(MarketIndex index, LocalDate latest, LocalDate today) {
        if (index.isDaily()) {
            return isDailyStale(latest, today);
        }
        LocalDate expectedMonth = today.minusDays(IPCA_PUBLISHED_BY_DAY).minusMonths(1).withDayOfMonth(1);
        return latest.isBefore(expectedMonth);
    }

    private static boolean isDailyStale(LocalDate latest, LocalDate today) {
        return latest == null || latest.isBefore(today.minusDays(DAILY_STALE_AFTER_DAYS));
    }

    /** No log para alerta (Loki): a tela mostra o mesmo, mas só para quem abrir. */
    private void warnIfStale(LocalDate today) {
        MarketIndexesResponse latest = getLatest(today);
        latest.indexes().stream()
                .filter(MarketIndexesResponse.IndexValue::stale)
                .forEach(value -> log.warn("Market data stale: source={} latest={}", value.index(), value.date()));
        if (latest.treasuryStale()) {
            log.warn("Market data stale: source=TESOURO latest={}", latest.treasuryBaseDate());
        }
    }

    /**
     * Busca do último dia gravado (inclusive) até hoje, em janelas que se sobrepõem em um dia:
     * o SGS recusa consulta com início igual ao fim, e repetir um dia custa só um upsert sem
     * efeito.
     */
    private int syncIndex(MarketIndex index, LocalDate initialStart, LocalDate today) {
        LocalDate start = marketDataRepository.findLatestRate(index).map(IndexRate::date).orElse(initialStart);
        int written = 0;
        while (start.isBefore(today)) {
            LocalDate end = start.plusYears(SGS_MAX_WINDOW_YEARS).minusDays(1);
            if (end.isAfter(today)) {
                end = today;
            }
            List<IndexRate> rates = bcbSgsClient.fetch(index, start, end);
            if (!rates.isEmpty()) {
                written += marketDataRepository.upsertRates(rates);
            }
            start = end;
        }
        return written;
    }

    public record SyncResult(int indexRates, int treasuryPrices, List<String> failedSources) {
    }
}
