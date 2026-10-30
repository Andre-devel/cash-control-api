package com.cashcontrol.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Cliente HTTP da Pluggy. As chamadas partem de um clique do usuário (ou de um webhook que a
 * Pluggy espera responder em 10 s), então os timeouts são curtos e não há nova tentativa: quem
 * clicou tenta de novo.
 */
@Configuration
public class PluggyClientConfig {

    @Bean
    RestClient pluggyRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
