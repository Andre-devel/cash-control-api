package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.MarketIndex;
import com.cashcontrol.api.service.marketdata.IndexRate;
import com.cashcontrol.api.service.marketdata.TreasuryBondPrice;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Índices e preços públicos ({@code market_index_rates}, {@code treasury_bond_prices}, V36).
 *
 * <p>JDBC em vez de JPA: a gravação é upsert em lote (a carga inicial do Tesouro passa de
 * 50 mil linhas) e as tabelas têm chave natural composta, sem identidade própria que
 * justifique uma entidade.
 */
@Repository
@RequiredArgsConstructor
public class MarketDataRepository {

    private final JdbcTemplate jdbcTemplate;

    /** Grava ou corrige cada valor; devolve quantas linhas mudaram. */
    public int upsertRates(List<IndexRate> rates) {
        int[][] counts = jdbcTemplate.batchUpdate(
                "INSERT INTO market_index_rates (market_index, rate_date, value) VALUES (?, ?, ?) "
                        + "ON CONFLICT (market_index, rate_date) DO UPDATE "
                        + "SET value = EXCLUDED.value, updated_at = NOW() "
                        + "WHERE market_index_rates.value <> EXCLUDED.value",
                rates, rates.size(),
                (ps, rate) -> {
                    ps.setString(1, rate.index().name());
                    ps.setObject(2, rate.date());
                    ps.setBigDecimal(3, rate.value());
                });
        return sum(counts);
    }

    /** Grava ou corrige cada preço; devolve quantas linhas mudaram. */
    public int upsertTreasuryPrices(List<TreasuryBondPrice> prices) {
        int[][] counts = jdbcTemplate.batchUpdate(
                "INSERT INTO treasury_bond_prices "
                        + "(bond_type, maturity_date, base_date, buy_rate, sell_rate, pu_buy, pu_sell, pu_base) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (bond_type, maturity_date, base_date) DO UPDATE "
                        + "SET buy_rate = EXCLUDED.buy_rate, sell_rate = EXCLUDED.sell_rate, "
                        + "pu_buy = EXCLUDED.pu_buy, pu_sell = EXCLUDED.pu_sell, pu_base = EXCLUDED.pu_base, "
                        + "updated_at = NOW() "
                        + "WHERE (treasury_bond_prices.buy_rate, treasury_bond_prices.sell_rate, "
                        + "treasury_bond_prices.pu_buy, treasury_bond_prices.pu_sell, treasury_bond_prices.pu_base) "
                        + "IS DISTINCT FROM (EXCLUDED.buy_rate, EXCLUDED.sell_rate, "
                        + "EXCLUDED.pu_buy, EXCLUDED.pu_sell, EXCLUDED.pu_base)",
                prices, prices.size(),
                (ps, price) -> {
                    ps.setString(1, price.bondType());
                    ps.setObject(2, price.maturityDate());
                    ps.setObject(3, price.baseDate());
                    ps.setBigDecimal(4, price.buyRate());
                    ps.setBigDecimal(5, price.sellRate());
                    ps.setBigDecimal(6, price.puBuy());
                    ps.setBigDecimal(7, price.puSell());
                    ps.setBigDecimal(8, price.puBase());
                });
        return sum(counts);
    }

    public Optional<IndexRate> findLatestRate(MarketIndex index) {
        return jdbcTemplate.query(
                        "SELECT rate_date, value FROM market_index_rates WHERE market_index = ? "
                                + "ORDER BY rate_date DESC LIMIT 1",
                        (rs, rowNum) -> new IndexRate(index, rs.getObject("rate_date", LocalDate.class),
                                rs.getBigDecimal("value")),
                        index.name())
                .stream().findFirst();
    }

    public Optional<LocalDate> findEarliestRateDate(MarketIndex index) {
        return Optional.ofNullable(jdbcTemplate.queryForObject(
                "SELECT MIN(rate_date) FROM market_index_rates WHERE market_index = ?", LocalDate.class, index.name()));
    }

    /** Valores de {@code index} entre {@code from} e {@code to}, inclusive, em ordem de data. */
    public List<IndexRate> findRates(MarketIndex index, LocalDate from, LocalDate to) {
        return jdbcTemplate.query(
                "SELECT rate_date, value FROM market_index_rates "
                        + "WHERE market_index = ? AND rate_date BETWEEN ? AND ? ORDER BY rate_date",
                (rs, rowNum) -> new IndexRate(index, rs.getObject("rate_date", LocalDate.class), rs.getBigDecimal("value")),
                index.name(), from, to);
    }

    /** Cotações de um título entre {@code from} e {@code to}, inclusive, em ordem de data. */
    public List<TreasuryBondPrice> findTreasuryPrices(String bondType, LocalDate maturityDate,
                                                      LocalDate from, LocalDate to) {
        return jdbcTemplate.query(
                "SELECT * FROM treasury_bond_prices WHERE bond_type = ? AND maturity_date = ? "
                        + "AND base_date BETWEEN ? AND ? ORDER BY base_date",
                (rs, rowNum) -> treasuryPrice(rs), bondType, maturityDate, from, to);
    }

    /** Cotação mais recente de um título até {@code onOrBefore}. */
    public Optional<TreasuryBondPrice> findTreasuryPrice(String bondType, LocalDate maturityDate, LocalDate onOrBefore) {
        return jdbcTemplate.query(
                        "SELECT * FROM treasury_bond_prices WHERE bond_type = ? AND maturity_date = ? "
                                + "AND base_date <= ? ORDER BY base_date DESC LIMIT 1",
                        (rs, rowNum) -> treasuryPrice(rs), bondType, maturityDate, onOrBefore)
                .stream().findFirst();
    }

    /** Todos os títulos cotados em {@code baseDate}. */
    public List<TreasuryBondPrice> findTreasuryPricesOn(LocalDate baseDate) {
        return jdbcTemplate.query(
                "SELECT * FROM treasury_bond_prices WHERE base_date = ? ORDER BY bond_type, maturity_date",
                (rs, rowNum) -> treasuryPrice(rs), baseDate);
    }

    public Optional<LocalDate> findLatestTreasuryBaseDate() {
        return Optional.ofNullable(jdbcTemplate.queryForObject(
                "SELECT MAX(base_date) FROM treasury_bond_prices", LocalDate.class));
    }

    private static TreasuryBondPrice treasuryPrice(ResultSet rs) throws SQLException {
        return new TreasuryBondPrice(
                rs.getString("bond_type"),
                rs.getObject("maturity_date", LocalDate.class),
                rs.getObject("base_date", LocalDate.class),
                rs.getBigDecimal("buy_rate"),
                rs.getBigDecimal("sell_rate"),
                rs.getBigDecimal("pu_buy"),
                rs.getBigDecimal("pu_sell"),
                rs.getBigDecimal("pu_base"));
    }

    private static int sum(int[][] counts) {
        return Arrays.stream(counts).flatMapToInt(Arrays::stream).sum();
    }
}
