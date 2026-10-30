package com.cashcontrol.api.service.investment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Valor de uma aplicação numa data: bruto, impostos de um resgate naquela data e líquido.
 *
 * @param principal    principal que o valor remunera (o ainda aplicado)
 * @param yield        {@code gross − principal}; negativo quando o Tesouro marca abaixo do custo
 * @param daysHeld     dias corridos desde a aplicação, base das tabelas de IR e IOF
 * @param estimated    algum dado veio de estimativa (índice ainda não publicado, sem cotação)
 */
public record Valuation(
        LocalDate valuedAt,
        BigDecimal principal,
        BigDecimal gross,
        BigDecimal incomeTax,
        BigDecimal iof,
        BigDecimal net,
        BigDecimal yield,
        BigDecimal incomeTaxRate,
        BigDecimal iofRate,
        long daysHeld,
        boolean estimated,
        ValuationSource source) {

    /** A fração {@code fraction} (0–1] deste valor, como num resgate parcial: impostos na mesma proporção. */
    public Valuation portion(BigDecimal fraction) {
        if (fraction.compareTo(BigDecimal.ONE) >= 0) {
            return this;
        }
        BigDecimal portionPrincipal = scale(principal, fraction);
        BigDecimal portionGross = scale(gross, fraction);
        BigDecimal portionIncomeTax = scale(incomeTax, fraction);
        BigDecimal portionIof = scale(iof, fraction);
        return new Valuation(valuedAt, portionPrincipal, portionGross, portionIncomeTax, portionIof,
                portionGross.subtract(portionIncomeTax).subtract(portionIof),
                portionGross.subtract(portionPrincipal), incomeTaxRate, iofRate, daysHeld, estimated, source);
    }

    private static BigDecimal scale(BigDecimal value, BigDecimal fraction) {
        return value.multiply(fraction).setScale(2, RoundingMode.HALF_EVEN);
    }
}
