package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.MarketIndex;
import com.cashcontrol.api.dto.response.MarketIndexesResponse;
import com.cashcontrol.api.security.JwtService;
import com.cashcontrol.api.service.marketdata.BcbSgsClient;
import com.cashcontrol.api.service.marketdata.IndexRate;
import com.cashcontrol.api.service.marketdata.MarketDataService;
import com.cashcontrol.api.service.marketdata.TesouroDiretoClient;
import com.cashcontrol.api.service.marketdata.TreasuryBondPrice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class MarketDataServiceIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);

    @Autowired private MarketDataService marketDataService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JwtService jwtService;

    @MockitoBean private BcbSgsClient bcbSgsClient;
    @MockitoBean private TesouroDiretoClient tesouroDiretoClient;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM market_index_rates");
        jdbcTemplate.update("DELETE FROM treasury_bond_prices");
        when(bcbSgsClient.fetch(any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void firstSync_fetchesFiveYearsOfHistory_andStoresEverySource() {
        when(bcbSgsClient.fetch(MarketIndex.CDI, TODAY.minusYears(5), TODAY))
                .thenReturn(List.of(rate(MarketIndex.CDI, 28, "0.050788"), rate(MarketIndex.CDI, 29, "0.050788")));
        when(bcbSgsClient.fetch(MarketIndex.IPCA, TODAY.minusYears(5), TODAY))
                .thenReturn(List.of(new IndexRate(MarketIndex.IPCA, LocalDate.of(2026, 9, 1), new BigDecimal("0.82"))));
        deliverTreasury(TODAY.minusYears(5).minusDays(1), List.of(price(29, "20182.01")));

        MarketDataService.SyncResult result = marketDataService.sync(TODAY);

        assertThat(result.indexRates()).isEqualTo(3);
        assertThat(result.treasuryPrices()).isEqualTo(1);
        assertThat(result.failedSources()).isEmpty();
        assertThat(count("market_index_rates")).isEqualTo(3);
        assertThat(count("treasury_bond_prices")).isEqualTo(1);
    }

    @Test
    void nextSync_resumesFromLatestStoredDate_withoutDuplicating() {
        when(bcbSgsClient.fetch(MarketIndex.CDI, TODAY.minusYears(5), TODAY))
                .thenReturn(List.of(rate(MarketIndex.CDI, 28, "0.050788")));
        marketDataService.sync(TODAY);

        // O último dia volta na sobreposição; só o dia novo é gravado.
        when(bcbSgsClient.fetch(MarketIndex.CDI, LocalDate.of(2026, 10, 28), TODAY))
                .thenReturn(List.of(rate(MarketIndex.CDI, 28, "0.050788"), rate(MarketIndex.CDI, 29, "0.050790")));
        MarketDataService.SyncResult second = marketDataService.sync(TODAY);

        assertThat(second.indexRates()).isEqualTo(1);
        assertThat(count("market_index_rates")).isEqualTo(2);
        verify(bcbSgsClient).fetch(MarketIndex.CDI, LocalDate.of(2026, 10, 28), TODAY);
    }

    @Test
    void sync_treasuryResumesAfterLatestBaseDate() {
        deliverTreasury(TODAY.minusYears(5).minusDays(1), List.of(price(28, "20171.90")));
        marketDataService.sync(TODAY);

        deliverTreasury(LocalDate.of(2026, 10, 28), List.of(price(29, "20182.01")));
        marketDataService.sync(TODAY);

        verify(tesouroDiretoClient).fetchPrices(eq(LocalDate.of(2026, 10, 28)), any());
        assertThat(count("treasury_bond_prices")).isEqualTo(2);
    }

    @Test
    void sync_failingSource_doesNotStopTheOthers() {
        when(bcbSgsClient.fetch(eq(MarketIndex.CDI), any(), any())).thenThrow(new ResourceAccessException("timeout"));
        when(bcbSgsClient.fetch(MarketIndex.SELIC, TODAY.minusYears(5), TODAY))
                .thenReturn(List.of(rate(MarketIndex.SELIC, 29, "0.050788")));
        deliverTreasury(TODAY.minusYears(5).minusDays(1), List.of(price(29, "20182.01")));

        MarketDataService.SyncResult result = marketDataService.sync(TODAY);

        assertThat(result.failedSources()).containsExactly("CDI");
        assertThat(result.indexRates()).isEqualTo(1);
        assertThat(result.treasuryPrices()).isEqualTo(1);
    }

    @Test
    void sync_moreThanTenYearsBehind_splitsIntoSgsWindows() {
        jdbcTemplate.update("INSERT INTO market_index_rates (market_index, rate_date, value) VALUES ('CDI', ?, 0.05)",
                LocalDate.of(2014, 1, 2));

        marketDataService.sync(TODAY);

        verify(bcbSgsClient).fetch(MarketIndex.CDI, LocalDate.of(2014, 1, 2), LocalDate.of(2024, 1, 1));
        verify(bcbSgsClient).fetch(MarketIndex.CDI, LocalDate.of(2024, 1, 1), TODAY);
    }

    @Test
    void latestIndexesEndpoint_returnsLatestValueOfEachIndex() throws Exception {
        when(bcbSgsClient.fetch(MarketIndex.CDI, TODAY.minusYears(5), TODAY))
                .thenReturn(List.of(rate(MarketIndex.CDI, 28, "0.050700"), rate(MarketIndex.CDI, 29, "0.050788")));
        deliverTreasury(TODAY.minusYears(5).minusDays(1), List.of(price(29, "20182.01")));
        marketDataService.sync(TODAY);

        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        mockMvc.perform(get("/api/v1/investments/indexes/latest")
                        .header("Authorization", "Bearer " + jwtService.generateToken(createUser(), List.of(), Instant.now())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexes.length()").value(3))
                .andExpect(jsonPath("$.indexes[0].index").value("CDI"))
                .andExpect(jsonPath("$.indexes[0].date").value("2026-10-29"))
                .andExpect(jsonPath("$.indexes[1].index").value("SELIC"))
                .andExpect(jsonPath("$.indexes[1].date").doesNotExist())
                .andExpect(jsonPath("$.indexes[1].stale").value(true))
                .andExpect(jsonPath("$.treasuryBaseDate").value("2026-10-29"));
    }

    @Test
    void latest_flagsSourcesOlderThanTheirNormalDelay() {
        insertRate(MarketIndex.CDI, LocalDate.of(2026, 10, 23));
        insertRate(MarketIndex.SELIC, LocalDate.of(2026, 10, 29));
        insertRate(MarketIndex.IPCA, LocalDate.of(2026, 9, 1));
        insertTreasury(LocalDate.of(2026, 10, 29));

        // 30/10: CDI parado há 7 dias; Selic de ontem; IPCA de setembro ainda é o esperado.
        MarketIndexesResponse latest = marketDataService.getLatest(TODAY);

        assertThat(latest.indexes()).extracting(MarketIndexesResponse.IndexValue::stale)
                .containsExactly(true, false, false);
        assertThat(latest.treasuryStale()).isFalse();

        // 16/11: o IPCA de outubro já devia ter saído; o Tesouro de 29/10 também ficou velho.
        MarketIndexesResponse later = marketDataService.getLatest(LocalDate.of(2026, 11, 16));
        assertThat(later.indexes().get(2).stale()).isTrue();
        assertThat(later.treasuryStale()).isTrue();
    }

    @Test
    void latest_carnivalGapIsNotStale() {
        // Carnaval de 2027: segunda 08/02 e terça 09/02 sem pregão; na quarta de manhã o último CDI é a sexta 05/02.
        insertRate(MarketIndex.CDI, LocalDate.of(2027, 2, 5));

        assertThat(marketDataService.getLatest(LocalDate.of(2027, 2, 10)).indexes().getFirst().stale()).isFalse();
    }

    private void insertRate(MarketIndex index, LocalDate date) {
        jdbcTemplate.update("INSERT INTO market_index_rates (market_index, rate_date, value) VALUES (?, ?, 0.05)",
                index.name(), date);
    }

    private void insertTreasury(LocalDate baseDate) {
        jdbcTemplate.update("INSERT INTO treasury_bond_prices (bond_type, maturity_date, base_date, buy_rate, sell_rate, "
                + "pu_buy, pu_sell, pu_base) VALUES ('Tesouro Selic', '2028-03-01', ?, 0.02, 0.03, 1, 1, 1)", baseDate);
    }

    @Test
    void latestIndexesEndpoint_requiresAuthentication() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        mockMvc.perform(get("/api/v1/investments/indexes/latest"))
                .andExpect(status().isUnauthorized());
    }

    private void deliverTreasury(LocalDate after, List<TreasuryBondPrice> prices) {
        when(tesouroDiretoClient.fetchPrices(eq(after), any())).thenAnswer(invocation -> {
            Consumer<List<TreasuryBondPrice>> sink = invocation.getArgument(1);
            sink.accept(prices);
            return prices.size();
        });
    }

    private static IndexRate rate(MarketIndex index, int octoberDay, String value) {
        return new IndexRate(index, LocalDate.of(2026, 10, octoberDay), new BigDecimal(value));
    }

    private static TreasuryBondPrice price(int octoberDay, String puBase) {
        BigDecimal pu = new BigDecimal(puBase);
        return new TreasuryBondPrice("Tesouro Selic", LocalDate.of(2028, 3, 1), LocalDate.of(2026, 10, octoberDay),
                new BigDecimal("0.02"), new BigDecimal("0.03"), pu, pu, pu);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private UUID createUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) "
                        + "VALUES (?, (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), "
                        + "(SELECT id FROM auth_origins WHERE slug = 'LOCAL'), NOW() - INTERVAL '1 minute') RETURNING id",
                UUID.class, "market-" + UUID.randomUUID() + "@example.com");
    }
}
