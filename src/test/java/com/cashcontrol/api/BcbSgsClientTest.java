package com.cashcontrol.api;

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.config.MarketDataClientConfig;
import com.cashcontrol.api.domain.entity.MarketIndex;
import com.cashcontrol.api.service.marketdata.BcbSgsClient;
import com.cashcontrol.api.service.marketdata.IndexRate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BcbSgsClientTest {

    private MockRestServiceServer server;
    private BcbSgsClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new BcbSgsClient(builder.build(), MarketDataClientConfig.retryTemplate(Duration.ofMillis(1)), new AppProperties());
    }

    @Test
    void fetch_parsesSgsJson_withSeriesAndBrazilianDatesInUrl() {
        server.expect(requestTo("https://api.bcb.gov.br/dados/serie/bcdata.sgs.12/dados"
                        + "?formato=json&dataInicial=28/10/2026&dataFinal=30/10/2026"))
                .andRespond(withSuccess("""
                        [{"data":"28/10/2026","valor":"0.050788"},{"data":"29/10/2026","valor":"0.050788"}]
                        """, MediaType.APPLICATION_JSON));

        List<IndexRate> rates = client.fetch(MarketIndex.CDI, LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 30));

        assertThat(rates).containsExactly(
                new IndexRate(MarketIndex.CDI, LocalDate.of(2026, 10, 28), new BigDecimal("0.050788")),
                new IndexRate(MarketIndex.CDI, LocalDate.of(2026, 10, 29), new BigDecimal("0.050788")));
        server.verify();
    }

    @Test
    void fetch_jsonServedAsHtml_isStillParsed() {
        // Resposta real do SGS para um período curto: corpo JSON com Content-Type text/html.
        server.expect(requestTo("https://api.bcb.gov.br/dados/serie/bcdata.sgs.12/dados"
                        + "?formato=json&dataInicial=29/10/2026&dataFinal=30/10/2026"))
                .andRespond(withSuccess("[{\"data\":\"29/10/2026\",\"valor\":\"0.050788\"}]",
                        MediaType.parseMediaType("text/html;charset=utf-8")));

        assertThat(client.fetch(MarketIndex.CDI, LocalDate.of(2026, 10, 29), LocalDate.of(2026, 10, 30)))
                .containsExactly(new IndexRate(MarketIndex.CDI, LocalDate.of(2026, 10, 29), new BigDecimal("0.050788")));
    }

    @Test
    void fetch_periodWithoutValues_returnsEmpty() {
        // Fim de semana: o SGS responde 404 "Value(s) not found" em vez de lista vazia.
        server.expect(requestTo("https://api.bcb.gov.br/dados/serie/bcdata.sgs.433/dados"
                        + "?formato=json&dataInicial=31/10/2026&dataFinal=01/11/2026"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"erro\":{\"statusCode\":404,\"detail\":\"Value(s) not found\"}}"));

        assertThat(client.fetch(MarketIndex.IPCA, LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1))).isEmpty();
        // 404 é resposta definitiva, não falha passageira: uma chamada só.
        server.verify();
    }

    @Test
    void fetch_transientServerError_isRetried() {
        String url = "https://api.bcb.gov.br/dados/serie/bcdata.sgs.11/dados"
                + "?formato=json&dataInicial=28/10/2026&dataFinal=30/10/2026";
        server.expect(once(), requestTo(url)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(once(), requestTo(url))
                .andRespond(withSuccess("[{\"data\":\"29/10/2026\",\"valor\":\"0.050788\"}]", MediaType.APPLICATION_JSON));

        assertThat(client.fetch(MarketIndex.SELIC, LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 30))).hasSize(1);
        server.verify();
    }

    @Test
    void fetch_persistentServerError_propagatesAfterRetries() {
        server.expect(times(3), requestTo("https://api.bcb.gov.br/dados/serie/bcdata.sgs.11/dados"
                        + "?formato=json&dataInicial=28/10/2026&dataFinal=30/10/2026"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.fetch(MarketIndex.SELIC, LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 30)))
                .isInstanceOf(HttpServerErrorException.class);
        server.verify();
    }

    @Test
    void fetch_sameDayRange_isRejectedBeforeCallingSgs() {
        LocalDate day = LocalDate.of(2026, 10, 29);

        assertThatThrownBy(() -> client.fetch(MarketIndex.CDI, day, day))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }
}
