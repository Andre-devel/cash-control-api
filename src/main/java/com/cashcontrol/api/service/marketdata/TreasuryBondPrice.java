package com.cashcontrol.api.service.marketdata;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Uma linha do CSV de preços e taxas do Tesouro Direto: o título ({@code bondType} +
 * {@code maturityDate}) cotado na {@code baseDate}. Taxas em % ao ano; PU em reais.
 */
public record TreasuryBondPrice(
        String bondType,
        LocalDate maturityDate,
        LocalDate baseDate,
        BigDecimal buyRate,
        BigDecimal sellRate,
        BigDecimal puBuy,
        BigDecimal puSell,
        BigDecimal puBase) {
}
