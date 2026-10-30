package com.cashcontrol.api.service.investment;

import com.cashcontrol.api.domain.entity.FixedIncomeHolding;
import com.cashcontrol.api.domain.entity.FixedIncomeIndexer;
import com.cashcontrol.api.domain.entity.FixedIncomeKind;
import com.cashcontrol.api.domain.entity.MarketIndex;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.function.DoubleUnaryOperator;

/**
 * Valor de uma aplicação de renda fixa numa data, com o IR e o IOF de um resgate nessa data.
 *
 * <p>É uma estimativa, não o extrato do banco: os bancos truncam fatores diários em casas
 * específicas, e o IPCA+ usa projeção ANBIMA para o mês corrente. A diferença fica em centavos
 * na maior parte dos casos; quando incomoda, o usuário informa o valor do app do banco e a curva
 * continua a partir dele.
 *
 * <p>Dias úteis vêm da série do CDI (uma linha por dia útil) — não há tabela de feriados. Dia
 * útil que ainda não tem CDI publicado (o de ontem, antes do job da manhã; os anteriores ao
 * histórico carregado) conta como dia de semana com o valor conhecido mais próximo, e o
 * resultado sai marcado como estimado.
 *
 * <p>As regras de IR e IOF são as vigentes para pessoa física em 2026 e mudam por lei; ficam
 * aqui como constantes, não como configuração, porque mudar uma tabela de imposto exige rever o
 * cálculo, não só um número.
 */
@Component
public class FixedIncomeCalculator {

    /** IOF regressivo: percentual do rendimento cobrado no resgate com N dias corridos (N < 30). */
    private static final int[] IOF_PERCENT = {
            100, 96, 93, 90, 86, 83, 80, 76, 73, 70, 66, 63, 60, 56, 53,
            50, 46, 43, 40, 36, 33, 30, 26, 23, 20, 16, 13, 10, 6, 3};

    private static final double BUSINESS_DAYS_PER_YEAR = 252;

    public Valuation value(FixedIncomeHolding holding, LocalDate date, MarketRates rates) {
        LocalDate at = date.isBefore(holding.getAppliedAt()) ? holding.getAppliedAt() : date;
        Gross gross = gross(holding, holding.getRemainingPrincipal(), holding.getTreasuryQuantity(), true, at, rates);
        return withTaxes(holding, at, holding.getRemainingPrincipal(), gross);
    }

    /**
     * Bruto sobre outra base — o principal e a quantidade de antes de um resgate, para a
     * evolução histórica. Ignora o valor informado manualmente, que só vale para o presente.
     */
    public BigDecimal grossAt(FixedIncomeHolding holding, BigDecimal principal, BigDecimal treasuryQuantity,
                              LocalDate date, MarketRates rates) {
        LocalDate at = date.isBefore(holding.getAppliedAt()) ? holding.getAppliedAt() : date;
        return gross(holding, principal, treasuryQuantity, false, at, rates).value();
    }

    /** Fator de 100% do CDI entre {@code from} e {@code to}: a referência de rentabilidade. */
    public double cdiFactor(LocalDate from, LocalDate to, MarketRates rates) {
        return compound(rates.series(MarketIndex.CDI), from, to, d -> 1 + d).value();
    }

    static BigDecimal incomeTaxPercent(long daysHeld) {
        if (daysHeld <= 180) {
            return new BigDecimal("22.5");
        }
        if (daysHeld <= 360) {
            return new BigDecimal("20");
        }
        if (daysHeld <= 720) {
            return new BigDecimal("17.5");
        }
        return new BigDecimal("15");
    }

    static BigDecimal iofPercent(long daysHeld) {
        return daysHeld < IOF_PERCENT.length ? BigDecimal.valueOf(IOF_PERCENT[(int) daysHeld]) : BigDecimal.ZERO;
    }

    private Valuation withTaxes(FixedIncomeHolding holding, LocalDate date, BigDecimal principal, Gross gross) {
        long daysHeld = ChronoUnit.DAYS.between(holding.getAppliedAt(), date);
        BigDecimal yield = gross.value().subtract(principal);
        BigDecimal taxable = yield.max(BigDecimal.ZERO);
        BigDecimal iofRate = holding.isTaxExempt() ? BigDecimal.ZERO : iofPercent(daysHeld);
        BigDecimal incomeTaxRate = holding.isTaxExempt() ? BigDecimal.ZERO : incomeTaxPercent(daysHeld);
        BigDecimal iof = percentOf(taxable, iofRate);
        BigDecimal incomeTax = percentOf(taxable.subtract(iof), incomeTaxRate);
        return new Valuation(date, principal, gross.value(), incomeTax, iof,
                gross.value().subtract(incomeTax).subtract(iof), yield,
                incomeTaxRate, iofRate, daysHeld, gross.estimated(), gross.source());
    }

    private Gross gross(FixedIncomeHolding holding, BigDecimal principal, BigDecimal treasuryQuantity,
                        boolean useManualValue, LocalDate date, MarketRates rates) {
        if (holding.getKind() == FixedIncomeKind.TESOURO_DIRETO) {
            Optional<Map.Entry<LocalDate, BigDecimal>> pu =
                    rates.treasuryPu(holding.getTreasuryBondType(), holding.getMaturityDate(), date);
            if (pu.isPresent() && treasuryQuantity != null) {
                return new Gross(money(treasuryQuantity.multiply(pu.get().getValue())), false, ValuationSource.MARKET);
            }
            return new Gross(principal, true, ValuationSource.COST);
        }

        BigDecimal base = principal;
        LocalDate from = holding.getAppliedAt();
        boolean manual = useManualValue && holding.getManualValue() != null
                && !holding.getManualValueDate().isAfter(date);
        if (manual) {
            base = holding.getManualValue();
            from = holding.getManualValueDate();
        }
        if (holding.getIndexer() == null) {
            return new Gross(base, !manual, manual ? ValuationSource.MANUAL : ValuationSource.COST);
        }
        Factor factor = factor(holding.getIndexer(), holding.getRate(), from, date, rates);
        return new Gross(money(base.multiply(BigDecimal.valueOf(factor.value()))), factor.estimated(), ValuationSource.CURVE);
    }

    private Factor factor(FixedIncomeIndexer indexer, BigDecimal ratePercent, LocalDate from, LocalDate to,
                          MarketRates rates) {
        double rate = ratePercent.doubleValue() / 100;
        NavigableMap<LocalDate, BigDecimal> cdi = rates.series(MarketIndex.CDI);
        return switch (indexer) {
            case CDI_PERCENT -> compound(cdi, from, to, d -> 1 + d * rate);
            case SELIC_PERCENT -> {
                NavigableMap<LocalDate, BigDecimal> selic = rates.series(MarketIndex.SELIC);
                yield compound(selic, from, to, d -> 1 + d * rate);
            }
            case CDI_SPREAD -> {
                double spread = Math.pow(1 + rate, 1 / BUSINESS_DAYS_PER_YEAR);
                yield compound(cdi, from, to, d -> (1 + d) * spread);
            }
            case PREFIXED -> {
                double daily = Math.pow(1 + rate, 1 / BUSINESS_DAYS_PER_YEAR);
                yield compound(cdi, from, to, d -> daily);
            }
            case IPCA_SPREAD -> {
                double daily = Math.pow(1 + rate, 1 / BUSINESS_DAYS_PER_YEAR);
                Factor real = compound(cdi, from, to, d -> daily);
                Factor inflation = inflation(rates.series(MarketIndex.IPCA), from, to);
                yield new Factor(real.value() * inflation.value(), real.estimated() || inflation.estimated());
            }
        };
    }

    /**
     * Produto do fator diário em cada dia útil de {@code [from, to)}. Os dias úteis são os da
     * série; o operador recebe o valor do dia como fração (0.0005 para 0,05%).
     */
    private static Factor compound(NavigableMap<LocalDate, BigDecimal> series, LocalDate from, LocalDate to,
                                   DoubleUnaryOperator dailyFactor) {
        if (!to.isAfter(from)) {
            return new Factor(1, false);
        }
        double factor = 1;
        boolean estimated = false;
        for (BigDecimal value : series.subMap(from, true, to, false).values()) {
            factor *= dailyFactor.applyAsDouble(value.doubleValue() / 100);
        }

        if (series.isEmpty()) {
            int days = weekdays(from, to);
            return new Factor(Math.pow(dailyFactor.applyAsDouble(0), days), days > 0);
        }
        LocalDate firstKnown = series.firstKey();
        if (from.isBefore(firstKnown)) {
            int days = weekdays(from, to.isBefore(firstKnown) ? to : firstKnown);
            factor *= Math.pow(dailyFactor.applyAsDouble(series.firstEntry().getValue().doubleValue() / 100), days);
            estimated = days > 0;
        }
        LocalDate afterLastKnown = series.lastKey().plusDays(1);
        if (afterLastKnown.isBefore(to)) {
            int days = weekdays(afterLastKnown.isBefore(from) ? from : afterLastKnown, to);
            factor *= Math.pow(dailyFactor.applyAsDouble(series.lastEntry().getValue().doubleValue() / 100), days);
            estimated |= days > 0;
        }
        return new Factor(factor, estimated);
    }

    /**
     * IPCA acumulado em {@code [from, to)}, pro rata por dias corridos dentro de cada mês. O mês
     * ainda não divulgado usa o último IPCA conhecido.
     */
    private static Factor inflation(NavigableMap<LocalDate, BigDecimal> ipca, LocalDate from, LocalDate to) {
        if (!to.isAfter(from)) {
            return new Factor(1, false);
        }
        double factor = 1;
        boolean estimated = false;
        for (YearMonth month = YearMonth.from(from); !month.atDay(1).isAfter(to.minusDays(1)); month = month.plusMonths(1)) {
            LocalDate start = from.isAfter(month.atDay(1)) ? from : month.atDay(1);
            LocalDate end = to.isBefore(month.plusMonths(1).atDay(1)) ? to : month.plusMonths(1).atDay(1);
            long days = ChronoUnit.DAYS.between(start, end);
            BigDecimal monthly = ipca.get(month.atDay(1));
            if (monthly == null) {
                Map.Entry<LocalDate, BigDecimal> nearest = ipca.floorEntry(month.atDay(1));
                if (nearest == null) {
                    nearest = ipca.firstEntry();
                }
                monthly = nearest == null ? BigDecimal.ZERO : nearest.getValue();
                estimated = true;
            }
            factor *= Math.pow(1 + monthly.doubleValue() / 100, days / (double) month.lengthOfMonth());
        }
        return new Factor(factor, estimated);
    }

    /** Dias de segunda a sexta em {@code [from, to)}. */
    private static int weekdays(LocalDate from, LocalDate to) {
        int count = 0;
        for (LocalDate day = from; day.isBefore(to); day = day.plusDays(1)) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                count++;
            }
        }
        return count;
    }

    private static BigDecimal percentOf(BigDecimal value, BigDecimal percent) {
        return money(value.multiply(percent).movePointLeft(2));
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_EVEN);
    }

    private record Gross(BigDecimal value, boolean estimated, ValuationSource source) {
    }

    private record Factor(double value, boolean estimated) {
    }
}
