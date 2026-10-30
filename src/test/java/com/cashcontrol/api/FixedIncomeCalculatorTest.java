package com.cashcontrol.api;

import com.cashcontrol.api.domain.entity.FixedIncomeHolding;
import com.cashcontrol.api.domain.entity.FixedIncomeIndexer;
import com.cashcontrol.api.domain.entity.FixedIncomeKind;
import com.cashcontrol.api.domain.entity.MarketIndex;
import com.cashcontrol.api.service.investment.FixedIncomeCalculator;
import com.cashcontrol.api.service.investment.MarketRates;
import com.cashcontrol.api.service.investment.Valuation;
import com.cashcontrol.api.service.investment.ValuationSource;
import com.cashcontrol.api.service.marketdata.IndexRate;
import com.cashcontrol.api.service.marketdata.TreasuryBondPrice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FixedIncomeCalculatorTest {

    /** Segunda-feira; 252 dias de semana a partir dela formam um "ano útil" de teste. */
    private static final LocalDate START = LocalDate.of(2025, 1, 6);

    /** CDI diário equivalente a 10% ao ano em 252 dias úteis, em %. */
    private static final BigDecimal CDI_10_PERCENT_DAILY = BigDecimal.valueOf((Math.pow(1.10, 1 / 252.0) - 1) * 100);

    private final FixedIncomeCalculator calculator = new FixedIncomeCalculator();

    @Test
    void cdiPercent_100_overOneBusinessYear_compoundsTheAnnualRate() {
        List<LocalDate> days = weekdays(START, 252);
        MarketRates rates = cdi(days);
        LocalDate end = days.getLast().plusDays(1);

        Valuation value = calculator.value(holding(FixedIncomeKind.CDB, FixedIncomeIndexer.CDI_PERCENT, "100"), end, rates);

        assertThat(value.gross()).isEqualByComparingTo("1100.00");
        assertThat(value.yield()).isEqualByComparingTo("100.00");
        assertThat(value.estimated()).isFalse();
        assertThat(value.source()).isEqualTo(ValuationSource.CURVE);
    }

    @Test
    void cdiPercent_110_appliesThePercentToEachDailyRate() {
        List<LocalDate> days = weekdays(START, 252);
        double daily = CDI_10_PERCENT_DAILY.doubleValue() / 100;
        BigDecimal expected = money(1000 * Math.pow(1 + daily * 1.10, 252));

        Valuation value = calculator.value(holding(FixedIncomeKind.CDB, FixedIncomeIndexer.CDI_PERCENT, "110"),
                days.getLast().plusDays(1), cdi(days));

        assertThat(value.gross()).isEqualByComparingTo(expected);
    }

    @Test
    void prefixed_usesBusinessDaysFromTheCdiCalendar() {
        List<LocalDate> days = weekdays(START, 252);

        Valuation value = calculator.value(holding(FixedIncomeKind.CDB, FixedIncomeIndexer.PREFIXED, "12"),
                days.getLast().plusDays(1), cdi(days));

        assertThat(value.gross()).isEqualByComparingTo("1120.00");
    }

    @Test
    void cdiSpread_addsTheSpreadOnTopOfCdi() {
        List<LocalDate> days = weekdays(START, 252);

        Valuation value = calculator.value(holding(FixedIncomeKind.CDB, FixedIncomeIndexer.CDI_SPREAD, "2"),
                days.getLast().plusDays(1), cdi(days));

        assertThat(value.gross()).isEqualByComparingTo(money(1000 * 1.10 * 1.02));
    }

    @Test
    void ipcaSpread_accruesInflationProRataPlusTheRealRate() {
        // Março e abril inteiros: IPCA de 1% em cada mês; 5% a.a. de juro real sobre os dias úteis.
        LocalDate from = LocalDate.of(2025, 3, 1);
        LocalDate to = LocalDate.of(2025, 5, 1);
        List<LocalDate> days = weekdaysBetween(from, to);
        MarketRates rates = cdi(days).addRates(List.of(
                new IndexRate(MarketIndex.IPCA, LocalDate.of(2025, 3, 1), new BigDecimal("1")),
                new IndexRate(MarketIndex.IPCA, LocalDate.of(2025, 4, 1), new BigDecimal("1"))));
        FixedIncomeHolding holding = holding(FixedIncomeKind.CDB, FixedIncomeIndexer.IPCA_SPREAD, "5");
        holding.setAppliedAt(from);

        Valuation value = calculator.value(holding, to, rates);

        assertThat(value.gross()).isEqualByComparingTo(money(1000 * 1.01 * 1.01 * Math.pow(1.05, days.size() / 252.0)));
        assertThat(value.estimated()).isFalse();
    }

    @Test
    void ipcaSpread_monthNotYetPublished_usesLastKnownAndIsEstimated() {
        LocalDate from = LocalDate.of(2025, 3, 1);
        LocalDate to = LocalDate.of(2025, 5, 1);
        MarketRates rates = cdi(weekdaysBetween(from, to)).addRates(List.of(
                new IndexRate(MarketIndex.IPCA, LocalDate.of(2025, 3, 1), new BigDecimal("1"))));
        FixedIncomeHolding holding = holding(FixedIncomeKind.CDB, FixedIncomeIndexer.IPCA_SPREAD, "5");
        holding.setAppliedAt(from);

        assertThat(calculator.value(holding, to, rates).estimated()).isTrue();
    }

    @Test
    void daysWithoutPublishedCdi_countAsWeekdaysWithTheLastValue_andAreEstimated() {
        List<LocalDate> days = weekdays(START, 250);
        // Os 2 últimos dias úteis ainda sem CDI publicado.
        LocalDate end = weekdays(START, 252).getLast().plusDays(1);

        Valuation value = calculator.value(holding(FixedIncomeKind.CDB, FixedIncomeIndexer.CDI_PERCENT, "100"), end, cdi(days));

        assertThat(value.gross()).isEqualByComparingTo("1100.00");
        assertThat(value.estimated()).isTrue();
    }

    @Test
    void incomeTax_followsTheRegressiveTable() {
        assertThat(taxRateAt(180)).isEqualByComparingTo("22.5");
        assertThat(taxRateAt(181)).isEqualByComparingTo("20");
        assertThat(taxRateAt(360)).isEqualByComparingTo("20");
        assertThat(taxRateAt(361)).isEqualByComparingTo("17.5");
        assertThat(taxRateAt(720)).isEqualByComparingTo("17.5");
        assertThat(taxRateAt(721)).isEqualByComparingTo("15");
    }

    @Test
    void iof_inTheFirst30Days_isChargedBeforeIncomeTax() {
        List<LocalDate> days = weekdays(START, 11);
        LocalDate fifteenDaysLater = START.plusDays(15);

        Valuation value = calculator.value(holding(FixedIncomeKind.CDB, FixedIncomeIndexer.PREFIXED, "50"),
                fifteenDaysLater, cdi(days));

        BigDecimal yield = value.gross().subtract(new BigDecimal("1000.00"));
        BigDecimal iof = money(yield.doubleValue() * 0.50);
        assertThat(value.iofRate()).isEqualByComparingTo("50");
        assertThat(value.iof()).isEqualByComparingTo(iof);
        assertThat(value.incomeTax()).isEqualByComparingTo(money(yield.subtract(iof).doubleValue() * 0.225));
        assertThat(value.net()).isEqualByComparingTo(value.gross().subtract(value.iof()).subtract(value.incomeTax()));
    }

    @Test
    void iof_disappearsOnDay30() {
        List<LocalDate> days = weekdays(START, 22);

        Valuation value = calculator.value(holding(FixedIncomeKind.CDB, FixedIncomeIndexer.PREFIXED, "12"),
                START.plusDays(30), cdi(days));

        assertThat(value.iofRate()).isZero();
        assertThat(value.iof()).isZero();
    }

    @Test
    void lci_isExemptFromIncomeTaxAndIof() {
        List<LocalDate> days = weekdays(START, 20);

        Valuation value = calculator.value(holding(FixedIncomeKind.LCI, FixedIncomeIndexer.CDI_PERCENT, "95"),
                days.getLast().plusDays(1), cdi(days));

        assertThat(value.yield()).isPositive();
        assertThat(value.incomeTax()).isZero();
        assertThat(value.iof()).isZero();
        assertThat(value.net()).isEqualByComparingTo(value.gross());
    }

    @Test
    void manualValue_isTheNewBase_andTheCurveContinuesFromIt() {
        List<LocalDate> days = weekdays(START, 252);
        FixedIncomeHolding holding = holding(FixedIncomeKind.CDB, FixedIncomeIndexer.CDI_PERCENT, "100");
        holding.setManualValue(new BigDecimal("1050.00"));
        holding.setManualValueDate(days.get(126));

        Valuation value = calculator.value(holding, days.getLast().plusDays(1), cdi(days));

        // 126 dos 252 dias úteis restam depois do valor informado: meio "ano útil" a 10%.
        assertThat(value.gross()).isEqualByComparingTo(money(1050 * Math.pow(1.10, 126 / 252.0)));
        assertThat(value.yield()).isEqualByComparingTo(value.gross().subtract(new BigDecimal("1000.00")));
    }

    @Test
    void otherWithoutIndexer_isWorthTheManualValue_orCostWithoutOne() {
        FixedIncomeHolding holding = holding(FixedIncomeKind.OTHER, null, null);
        LocalDate today = START.plusDays(100);

        Valuation atCost = calculator.value(holding, today, new MarketRates());
        holding.setManualValue(new BigDecimal("1080.00"));
        holding.setManualValueDate(START.plusDays(90));
        Valuation informed = calculator.value(holding, today, new MarketRates());

        assertThat(atCost.source()).isEqualTo(ValuationSource.COST);
        assertThat(atCost.gross()).isEqualByComparingTo("1000.00");
        assertThat(atCost.estimated()).isTrue();
        assertThat(informed.source()).isEqualTo(ValuationSource.MANUAL);
        assertThat(informed.gross()).isEqualByComparingTo("1080.00");
    }

    @Test
    void tesouro_isQuantityTimesTheLatestBasePrice_andCanLose() {
        FixedIncomeHolding holding = holding(FixedIncomeKind.TESOURO_DIRETO, null, null);
        holding.setTreasuryBondType("Tesouro Prefixado");
        holding.setMaturityDate(LocalDate.of(2031, 1, 1));
        holding.setTreasuryQuantity(new BigDecimal("1.5"));
        MarketRates rates = new MarketRates().addTreasuryPrices(List.of(
                price(START.plusDays(10), "640.00"),
                price(START.plusDays(20), "650.00")));

        Valuation value = calculator.value(holding, START.plusDays(25), rates);

        assertThat(value.source()).isEqualTo(ValuationSource.MARKET);
        assertThat(value.gross()).isEqualByComparingTo("975.00");
        assertThat(value.yield()).isEqualByComparingTo("-25.00");
        assertThat(value.incomeTax()).isZero();
    }

    @Test
    void tesouroWithoutPrice_isWorthCost_andEstimated() {
        FixedIncomeHolding holding = holding(FixedIncomeKind.TESOURO_DIRETO, null, null);
        holding.setTreasuryBondType("Tesouro Selic");
        holding.setMaturityDate(LocalDate.of(2029, 3, 1));
        holding.setTreasuryQuantity(new BigDecimal("0.05"));

        Valuation value = calculator.value(holding, START.plusDays(5), new MarketRates());

        assertThat(value.source()).isEqualTo(ValuationSource.COST);
        assertThat(value.estimated()).isTrue();
    }

    @Test
    void portion_splitsPrincipalGrossAndTaxesProportionally() {
        List<LocalDate> days = weekdays(START, 252);
        Valuation full = calculator.value(holding(FixedIncomeKind.CDB, FixedIncomeIndexer.CDI_PERCENT, "100"),
                days.getLast().plusDays(1), cdi(days));

        Valuation quarter = full.portion(new BigDecimal("0.25"));

        assertThat(quarter.principal()).isEqualByComparingTo("250.00");
        assertThat(quarter.gross()).isEqualByComparingTo("275.00");
        assertThat(quarter.incomeTax()).isEqualByComparingTo(full.incomeTax().divide(new BigDecimal("4"), 2, RoundingMode.HALF_EVEN));
        assertThat(quarter.net()).isEqualByComparingTo(quarter.gross().subtract(quarter.incomeTax()).subtract(quarter.iof()));
    }

    @Test
    void cdiFactor_isTheBenchmarkOverThePeriod() {
        List<LocalDate> days = weekdays(START, 252);

        assertThat(calculator.cdiFactor(START, days.getLast().plusDays(1), cdi(days)))
                .isCloseTo(1.10, org.assertj.core.data.Offset.offset(1e-9));
    }

    private BigDecimal taxRateAt(int daysHeld) {
        FixedIncomeHolding holding = holding(FixedIncomeKind.CDB, FixedIncomeIndexer.PREFIXED, "10");
        return calculator.value(holding, START.plusDays(daysHeld), cdi(weekdaysBetween(START, START.plusDays(daysHeld))))
                .incomeTaxRate();
    }

    private static FixedIncomeHolding holding(FixedIncomeKind kind, FixedIncomeIndexer indexer, String rate) {
        FixedIncomeHolding holding = new FixedIncomeHolding();
        holding.setKind(kind);
        holding.setIndexer(indexer);
        holding.setRate(rate == null ? null : new BigDecimal(rate));
        holding.setTaxExempt(kind.isTaxExempt());
        holding.setAppliedAt(START);
        holding.setPrincipal(new BigDecimal("1000.00"));
        holding.setRemainingPrincipal(new BigDecimal("1000.00"));
        return holding;
    }

    private static MarketRates cdi(List<LocalDate> days) {
        return new MarketRates().addRates(days.stream()
                .map(day -> new IndexRate(MarketIndex.CDI, day, CDI_10_PERCENT_DAILY))
                .toList());
    }

    private static TreasuryBondPrice price(LocalDate baseDate, String puBase) {
        BigDecimal pu = new BigDecimal(puBase);
        return new TreasuryBondPrice("Tesouro Prefixado", LocalDate.of(2031, 1, 1), baseDate,
                new BigDecimal("13"), new BigDecimal("13.1"), pu, pu, pu);
    }

    private static List<LocalDate> weekdays(LocalDate from, int count) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate day = from; days.size() < count; day = day.plusDays(1)) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.add(day);
            }
        }
        return days;
    }

    private static List<LocalDate> weekdaysBetween(LocalDate from, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate day = from; day.isBefore(to); day = day.plusDays(1)) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.add(day);
            }
        }
        return days;
    }

    private static BigDecimal money(double value) {
        return new BigDecimal(value, MathContext.DECIMAL64).setScale(2, RoundingMode.HALF_EVEN);
    }
}
