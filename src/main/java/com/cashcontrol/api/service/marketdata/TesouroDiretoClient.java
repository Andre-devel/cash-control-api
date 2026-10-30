package com.cashcontrol.api.service.marketdata;

import com.cashcontrol.api.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * CSV de preços e taxas do Tesouro Direto publicado no Tesouro Transparente.
 *
 * <p>Formato real (ISO-8859-1, {@code ;}, vírgula decimal, histórico inteiro desde 2004 em
 * um arquivo só, ~15 MB):
 *
 * <pre>
 * Tipo Titulo;Data Vencimento;Data Base;Taxa Compra Manha;Taxa Venda Manha;PU Compra Manha;PU Venda Manha;PU Base Manha
 * Tesouro Selic;01/03/2028;29/10/2026;0,02;0,03;20195,01;20182,01;20182,01
 * </pre>
 *
 * <p>As linhas não vêm em ordem de data, então o arquivo é sempre lido até o fim e filtrado
 * por {@code baseDate}. A leitura é em streaming e entregue em lotes, para não montar o
 * histórico inteiro em memória.
 *
 * <p>O arquivo muda uma vez por dia útil e o servidor responde {@code 304} a
 * {@code If-Modified-Since}: guardando o {@code Last-Modified} da última leitura completa, a
 * execução que encontra o mesmo arquivo não baixa os 15 MB de novo. O valor fica só em memória
 * — depois de um restart, a primeira execução baixa uma vez e volta a valer.
 */
@Component
@Slf4j
public class TesouroDiretoClient {

    static final int BATCH_SIZE = 1000;

    private static final DateTimeFormatter CSV_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final List<String> COLUMNS = List.of(
            "Tipo Titulo", "Data Vencimento", "Data Base",
            "Taxa Compra Manha", "Taxa Venda Manha",
            "PU Compra Manha", "PU Venda Manha", "PU Base Manha");

    private final RestClient restClient;
    private final RetryTemplate retryTemplate;
    private final AppProperties appProperties;

    /** {@code Last-Modified} do último arquivo lido até o fim; {@code null} antes da primeira leitura. */
    private volatile String lastModified;

    public TesouroDiretoClient(RestClient marketDataRestClient, RetryTemplate marketDataRetryTemplate,
                               AppProperties appProperties) {
        this.restClient = marketDataRestClient;
        this.retryTemplate = marketDataRetryTemplate;
        this.appProperties = appProperties;
    }

    /**
     * Baixa o CSV e entrega a {@code sink}, em lotes, as linhas com {@code baseDate} posterior a
     * {@code after}. Devolve o total entregue — zero também quando o arquivo não mudou desde a
     * última leitura.
     */
    public int fetchPrices(LocalDate after, Consumer<List<TreasuryBondPrice>> sink) {
        URI uri = URI.create(appProperties.getMarketData().getTreasuryCsvUrl());
        return retryTemplate.invoke(() -> download(uri, after, sink));
    }

    private int download(URI uri, LocalDate after, Consumer<List<TreasuryBondPrice>> sink) {
        String known = lastModified;
        Integer count = restClient.get().uri(uri)
                .headers(headers -> {
                    if (known != null) {
                        headers.set(HttpHeaders.IF_MODIFIED_SINCE, known);
                    }
                })
                .exchange((request, response) -> {
                    if (response.getStatusCode().isSameCodeAs(HttpStatus.NOT_MODIFIED)) {
                        log.debug("Treasury CSV not modified since {}", known);
                        return 0;
                    }
                    if (response.getStatusCode().is5xxServerError()) {
                        throw new HttpServerErrorException(response.getStatusCode());
                    }
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new RestClientException("CSV do Tesouro respondeu " + response.getStatusCode());
                    }
                    int parsed;
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(response.getBody(), StandardCharsets.ISO_8859_1))) {
                        parsed = parse(reader, after, sink);
                    }
                    // Só depois da leitura completa: um arquivo lido pela metade precisa ser baixado de novo.
                    lastModified = response.getHeaders().getFirst(HttpHeaders.LAST_MODIFIED);
                    return parsed;
                });
        return count == null ? 0 : count;
    }

    static int parse(BufferedReader reader, LocalDate after, Consumer<List<TreasuryBondPrice>> sink) {
        try {
            String header = reader.readLine();
            int[] positions = columnPositions(header);
            List<TreasuryBondPrice> batch = new ArrayList<>(BATCH_SIZE);
            int count = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] fields = line.split(";", -1);
                LocalDate baseDate = LocalDate.parse(fields[positions[2]].trim(), CSV_DATE);
                if (!baseDate.isAfter(after)) {
                    continue;
                }
                batch.add(new TreasuryBondPrice(
                        fields[positions[0]].trim(),
                        LocalDate.parse(fields[positions[1]].trim(), CSV_DATE),
                        baseDate,
                        decimal(fields[positions[3]]),
                        decimal(fields[positions[4]]),
                        decimal(fields[positions[5]]),
                        decimal(fields[positions[6]]),
                        decimal(fields[positions[7]])));
                if (batch.size() == BATCH_SIZE) {
                    sink.accept(batch);
                    count += batch.size();
                    batch = new ArrayList<>(BATCH_SIZE);
                }
            }
            if (!batch.isEmpty()) {
                sink.accept(batch);
                count += batch.size();
            }
            return count;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Posição de cada coluna esperada, pelo nome. Um cabeçalho diferente aborta a leitura:
     * gravar preço na coluna errada é pior do que ficar um dia sem atualizar.
     */
    private static int[] columnPositions(String header) {
        if (header == null) {
            throw new IllegalStateException("CSV do Tesouro veio vazio");
        }
        List<String> names = Arrays.stream(header.split(";", -1)).map(String::trim).toList();
        int[] positions = new int[COLUMNS.size()];
        for (int i = 0; i < COLUMNS.size(); i++) {
            positions[i] = names.indexOf(COLUMNS.get(i));
            if (positions[i] < 0) {
                throw new IllegalStateException("CSV do Tesouro sem a coluna '" + COLUMNS.get(i) + "': " + header);
            }
        }
        return positions;
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value.trim().replace(".", "").replace(',', '.'));
    }
}
