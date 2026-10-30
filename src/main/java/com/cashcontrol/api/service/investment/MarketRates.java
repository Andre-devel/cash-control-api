package com.cashcontrol.api.service.investment;

import com.cashcontrol.api.domain.entity.MarketIndex;
import com.cashcontrol.api.service.marketdata.IndexRate;
import com.cashcontrol.api.service.marketdata.TreasuryBondPrice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Índices e preços já carregados para um período, consultados em memória pelo
 * {@link FixedIncomeCalculator}. Montado uma vez por pedido: avaliar uma carteira inteira ou
 * a evolução mês a mês de uma aplicação não volta ao banco a cada dia.
 */
public final class MarketRates {

    private final Map<MarketIndex, NavigableMap<LocalDate, BigDecimal>> indexes = new EnumMap<>(MarketIndex.class);
    private final Map<TreasuryKey, NavigableMap<LocalDate, BigDecimal>> treasuryPrices = new HashMap<>();

    public MarketRates() {
        for (MarketIndex index : MarketIndex.values()) {
            indexes.put(index, new TreeMap<>());
        }
    }

    public MarketRates addRates(Collection<IndexRate> rates) {
        rates.forEach(rate -> indexes.get(rate.index()).put(rate.date(), rate.value()));
        return this;
    }

    public MarketRates addTreasuryPrices(Collection<TreasuryBondPrice> prices) {
        prices.forEach(price -> treasuryPrices
                .computeIfAbsent(new TreasuryKey(price.bondType(), price.maturityDate()), key -> new TreeMap<>())
                .put(price.baseDate(), price.puBase()));
        return this;
    }

    /** Série do índice, por data. CDI e Selic: % ao dia útil; IPCA: % no mês, datado no dia 1. */
    NavigableMap<LocalDate, BigDecimal> series(MarketIndex index) {
        return indexes.get(index);
    }

    /** PU base mais recente do título até {@code date}. */
    Optional<Map.Entry<LocalDate, BigDecimal>> treasuryPu(String bondType, LocalDate maturityDate, LocalDate date) {
        NavigableMap<LocalDate, BigDecimal> prices = treasuryPrices.get(new TreasuryKey(bondType, maturityDate));
        return prices == null ? Optional.empty() : Optional.ofNullable(prices.floorEntry(date));
    }

    private record TreasuryKey(String bondType, LocalDate maturityDate) {
    }
}
