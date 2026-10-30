package com.cashcontrol.api.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryState;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Cliente HTTP das fontes públicas de índices e preços (BCB e Tesouro). Os timeouts são
 * explícitos porque o job roda sem ninguém olhando: sem eles, uma fonte fora do ar prende a
 * execução indefinidamente.
 *
 * <p>As duas fontes falham de forma passageira (o SGS devolve 502 de vez em quando), e sem nova
 * tentativa o índice ficaria parado até a execução seguinte do job, horas depois.
 */
@Configuration
@Slf4j
public class MarketDataClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** O CSV do Tesouro tem ~15 MB com o histórico inteiro. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(90);

    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);

    @Bean
    RestClient marketDataRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    @Bean
    RetryTemplate marketDataRetryTemplate() {
        return retryTemplate(RETRY_DELAY);
    }

    /**
     * Até duas novas tentativas (espera de {@code delay}, depois o dobro), só para falha do lado
     * da fonte: 5xx, timeout ou conexão. Um 4xx ou um arquivo em formato inesperado não melhora
     * repetindo.
     */
    public static RetryTemplate retryTemplate(Duration delay) {
        RetryTemplate template = new RetryTemplate(RetryPolicy.builder()
                .maxRetries(2)
                .delay(delay)
                .multiplier(2)
                .includes(HttpServerErrorException.class, ResourceAccessException.class)
                .build());
        // Sem isto a nova tentativa que deu certo não deixa rastro, e uma fonte que falha todo dia
        // passa despercebida até falhar de vez.
        template.setRetryListener(new RetryListener() {
            @Override
            public void beforeRetry(RetryPolicy policy, Retryable<?> retryable, RetryState state) {
                log.info("Market data retry: attempt={} cause={}", state.getRetryCount() + 1, state.getLastException().toString());
            }
        });
        return template;
    }
}
