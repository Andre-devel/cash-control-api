package com.cashcontrol.api.service.marketdata;

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.entity.MarketIndex;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * Séries do SGS do Banco Central ({@code /dados/serie/bcdata.sgs.{série}/dados}).
 *
 * <p>Regras da API que moldam o uso:
 * <ul>
 *   <li>{@code dataFinal} precisa ser maior que {@code dataInicial} — o mesmo dia nas duas
 *       volta 400;</li>
 *   <li>séries diárias aceitam no máximo 10 anos por consulta;</li>
 *   <li>período sem nenhum valor (fim de semana, feriado) volta 404, não lista vazia;</li>
 *   <li>o corpo é JSON, mas parte das respostas vem com {@code Content-Type: text/html} — por
 *       isso a leitura é de texto, convertido aqui, e não pelos conversores do RestClient.</li>
 * </ul>
 */
@Component
public class BcbSgsClient {

    private static final DateTimeFormatter SGS_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient restClient;
    private final RetryTemplate retryTemplate;
    private final AppProperties appProperties;

    public BcbSgsClient(RestClient marketDataRestClient, RetryTemplate marketDataRetryTemplate,
                        AppProperties appProperties) {
        this.restClient = marketDataRestClient;
        this.retryTemplate = marketDataRetryTemplate;
        this.appProperties = appProperties;
    }

    /** Valores de {@code index} entre {@code from} e {@code to}, inclusive; {@code to} > {@code from}. */
    public List<IndexRate> fetch(MarketIndex index, LocalDate from, LocalDate to) {
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("SGS exige dataFinal maior que dataInicial: " + from + " / " + to);
        }
        URI uri = UriComponentsBuilder.fromUriString(appProperties.getMarketData().getBcbBaseUrl())
                .path("/dados/serie/bcdata.sgs.{series}/dados")
                .queryParam("formato", "json")
                .queryParam("dataInicial", SGS_DATE.format(from))
                .queryParam("dataFinal", SGS_DATE.format(to))
                .buildAndExpand(index.getSgsSeries())
                .toUri();
        String body;
        try {
            body = retryTemplate.invoke(() -> restClient.get().uri(uri).retrieve().body(String.class));
        } catch (HttpClientErrorException.NotFound e) {
            return List.of();
        }
        if (body == null || body.isBlank()) {
            return List.of();
        }
        return Arrays.stream(JSON.readValue(body, SgsValue[].class))
                .map(value -> new IndexRate(index, LocalDate.parse(value.data(), SGS_DATE), new BigDecimal(value.valor())))
                .toList();
    }

    record SgsValue(String data, String valor) {
    }
}
