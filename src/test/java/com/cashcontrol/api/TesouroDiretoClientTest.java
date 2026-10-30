package com.cashcontrol.api;

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.config.MarketDataClientConfig;
import com.cashcontrol.api.service.marketdata.TesouroDiretoClient;
import com.cashcontrol.api.service.marketdata.TreasuryBondPrice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpServerErrorException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TesouroDiretoClientTest {

    private static final String HEADER = "Tipo Titulo;Data Vencimento;Data Base;Taxa Compra Manha;"
            + "Taxa Venda Manha;PU Compra Manha;PU Venda Manha;PU Base Manha\n";

    private MockRestServiceServer server;
    private TesouroDiretoClient client;
    private String csvUrl;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        csvUrl = properties.getMarketData().getTreasuryCsvUrl();
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TesouroDiretoClient(builder.build(), MarketDataClientConfig.retryTemplate(Duration.ofMillis(1)), properties);
    }

    @Test
    void fetchPrices_parsesRowsAfterGivenDate_withDecimalComma() {
        respondWith(HEADER
                + "Tesouro Selic;01/03/2028;29/10/2026;0,02;0,03;20195,01;20182,01;20182,01\n"
                + "Tesouro IPCA+ com Juros Semestrais;15/05/2035;29/10/2026;7,41;7,53;4.312,77;4.270,05;4.270,05\n"
                + "Tesouro Selic;01/03/2028;28/10/2026;0,02;0,03;20184,90;20171,90;20171,90\n");
        List<TreasuryBondPrice> received = new ArrayList<>();

        int count = client.fetchPrices(LocalDate.of(2026, 10, 28), received::addAll);

        assertThat(count).isEqualTo(2);
        assertThat(received).containsExactly(
                new TreasuryBondPrice("Tesouro Selic", LocalDate.of(2028, 3, 1), LocalDate.of(2026, 10, 29),
                        new BigDecimal("0.02"), new BigDecimal("0.03"),
                        new BigDecimal("20195.01"), new BigDecimal("20182.01"), new BigDecimal("20182.01")),
                new TreasuryBondPrice("Tesouro IPCA+ com Juros Semestrais", LocalDate.of(2035, 5, 15),
                        LocalDate.of(2026, 10, 29),
                        new BigDecimal("7.41"), new BigDecimal("7.53"),
                        new BigDecimal("4312.77"), new BigDecimal("4270.05"), new BigDecimal("4270.05")));
    }

    @Test
    void fetchPrices_deliversInBatches() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 0; i < 2500; i++) {
            csv.append("Tesouro Prefixado;01/01/2029;").append(LocalDate.of(2020, 1, 1).plusDays(i)
                            .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")))
                    .append(";13,10;13,22;712,04;709,88;709,88\n");
        }
        respondWith(csv.toString());
        List<Integer> batchSizes = new ArrayList<>();

        int count = client.fetchPrices(LocalDate.of(2019, 12, 31), batch -> batchSizes.add(batch.size()));

        assertThat(count).isEqualTo(2500);
        assertThat(batchSizes).containsExactly(1000, 1000, 500);
    }

    @Test
    void fetchPrices_unexpectedHeader_abortsWithoutDeliveringRows() {
        respondWith("Tipo;Vencimento;Data;Taxa;PU\nTesouro Selic;01/03/2028;29/10/2026;0,02;20195,01\n");
        List<TreasuryBondPrice> received = new ArrayList<>();

        assertThatThrownBy(() -> client.fetchPrices(LocalDate.of(2026, 10, 1), received::addAll))
                .hasMessageContaining("Tipo Titulo");
        assertThat(received).isEmpty();
    }

    @Test
    void fetchPrices_sameFileAsLastRead_isNotDownloadedAgain() {
        String lastModified = "Fri, 30 Oct 2026 10:21:20 GMT";
        server.expect(once(), requestTo(csvUrl))
                .andExpect(headerDoesNotExist(HttpHeaders.IF_MODIFIED_SINCE))
                .andRespond(withSuccess(csvBytes(HEADER + "Tesouro Selic;01/03/2028;29/10/2026;0,02;0,03;20195,01;20182,01;20182,01\n"),
                        MediaType.parseMediaType("text/csv")).header(HttpHeaders.LAST_MODIFIED, lastModified));
        server.expect(once(), requestTo(csvUrl))
                .andExpect(header(HttpHeaders.IF_MODIFIED_SINCE, lastModified))
                .andRespond(withStatus(HttpStatus.NOT_MODIFIED));
        List<TreasuryBondPrice> received = new ArrayList<>();

        assertThat(client.fetchPrices(LocalDate.of(2026, 10, 1), received::addAll)).isEqualTo(1);
        assertThat(client.fetchPrices(LocalDate.of(2026, 10, 1), received::addAll)).isZero();
        assertThat(received).hasSize(1);
        server.verify();
    }

    @Test
    void fetchPrices_failedRead_isDownloadedInFullNextTime() {
        server.expect(once(), requestTo(csvUrl))
                .andRespond(withSuccess(csvBytes("Tipo;Data\n"), MediaType.parseMediaType("text/csv"))
                        .header(HttpHeaders.LAST_MODIFIED, "Fri, 30 Oct 2026 10:21:20 GMT"));
        server.expect(once(), requestTo(csvUrl))
                .andExpect(headerDoesNotExist(HttpHeaders.IF_MODIFIED_SINCE))
                .andRespond(withSuccess(csvBytes(HEADER), MediaType.parseMediaType("text/csv")));

        assertThatThrownBy(() -> client.fetchPrices(LocalDate.of(2026, 10, 1), batch -> { }));
        assertThat(client.fetchPrices(LocalDate.of(2026, 10, 1), batch -> { })).isZero();
        server.verify();
    }

    @Test
    void fetchPrices_transientServerError_isRetried() {
        server.expect(once(), requestTo(csvUrl)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        respondWith(HEADER + "Tesouro Selic;01/03/2028;29/10/2026;0,02;0,03;20195,01;20182,01;20182,01\n");

        assertThat(client.fetchPrices(LocalDate.of(2026, 10, 1), batch -> { })).isEqualTo(1);
        server.verify();
    }

    @Test
    void fetchPrices_persistentServerError_throwsAfterRetries() {
        server.expect(times(3), requestTo(csvUrl)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.fetchPrices(LocalDate.of(2026, 10, 1), batch -> { }))
                .isInstanceOf(HttpServerErrorException.class);
        server.verify();
    }

    private void respondWith(String csv) {
        server.expect(once(), requestTo(csvUrl))
                .andRespond(withSuccess(csvBytes(csv), MediaType.parseMediaType("text/csv")));
    }

    private static byte[] csvBytes(String csv) {
        return csv.getBytes(StandardCharsets.ISO_8859_1);
    }
}
