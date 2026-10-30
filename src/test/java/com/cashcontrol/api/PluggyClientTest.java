package com.cashcontrol.api;

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.exception.BankProviderException;
import com.cashcontrol.api.service.openfinance.PluggyClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PluggyClientTest {

    private static final String BASE = "https://api.pluggy.ai";
    private static final String ITEM = "8d3c1f0e-1111-4222-8333-944455556666";

    private MockRestServiceServer server;
    private RestClient restClient;
    private AppProperties properties;
    private MutableClock clock;
    private PluggyClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
        properties = new AppProperties();
        properties.getPluggy().setClientId("client-id");
        properties.getPluggy().setClientSecret("client-secret");
        clock = new MutableClock(Instant.parse("2026-10-30T12:00:00Z"));
        client = new PluggyClient(restClient, properties, clock);
    }

    @Test
    void connectToken_authenticatesOnce_andSendsUserAndWebhook() {
        properties.getPluggy().setWebhookUrl("https://cashcontrol.example.com/api/v1/webhooks/pluggy");
        expectAuth("key-1");
        server.expect(requestTo(BASE + "/connect_token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-KEY", "key-1"))
                .andExpect(content().json("""
                        {"options":{"clientUserId":"user-1","avoidDuplicates":true,
                         "webhookUrl":"https://cashcontrol.example.com/api/v1/webhooks/pluggy"}}
                        """, org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withSuccess("{\"accessToken\":\"tok-1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/connect_token"))
                .andExpect(header("X-API-KEY", "key-1"))
                .andExpect(content().json("{\"itemId\":\"" + ITEM + "\"}"))
                .andRespond(withSuccess("{\"accessToken\":\"tok-2\"}", MediaType.APPLICATION_JSON));

        assertThat(client.createConnectToken("user-1", null)).isEqualTo("tok-1");
        assertThat(client.createConnectToken("user-1", ITEM)).isEqualTo("tok-2");
        server.verify();
    }

    @Test
    void apiKey_isRenewedBeforeItsTwoHoursRunOut() {
        expectAuth("key-1");
        expectAccounts("key-1");
        expectAuth("key-2");
        expectAccounts("key-2");

        client.listAccounts(ITEM);
        clock.advance(Duration.ofMinutes(116));
        client.listAccounts(ITEM);
        server.verify();
    }

    @Test
    void rejectedApiKey_isDiscardedAndTheCallRepeatedOnce() {
        expectAuth("key-1");
        server.expect(requestTo(BASE + "/accounts?itemId=" + ITEM)).andExpect(header("X-API-KEY", "key-1"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        expectAuth("key-2");
        expectAccounts("key-2");

        assertThat(client.listAccounts(ITEM)).hasSize(2);
        server.verify();
    }

    @Test
    void listAccounts_readsTheResultsPage() {
        expectAuth("key-1");
        expectAccounts("key-1");

        var accounts = client.listAccounts(ITEM);

        assertThat(accounts).extracting(PluggyClient.PluggyAccount::type).containsExactly("BANK", "CREDIT");
        assertThat(accounts.get(1).marketingName()).isEqualTo("Inter Black");
    }

    @Test
    void getItem_unknownItem_isEmpty() {
        expectAuth("key-1");
        server.expect(requestTo(BASE + "/items/" + ITEM)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.getItem(ITEM)).isEmpty();
    }

    @Test
    void getItem_readsStatusOwnerAndConnector() {
        expectAuth("key-1");
        server.expect(requestTo(BASE + "/items/" + ITEM)).andRespond(withSuccess("""
                {"id":"%s","status":"LOGIN_ERROR","executionStatus":"INVALID_CREDENTIALS","clientUserId":"user-1",
                 "lastUpdatedAt":"2026-10-30T10:00:00.000Z","connector":{"id":200,"name":"Inter"},
                 "error":{"code":"INVALID_CREDENTIALS","message":"Consentimento expirado"},"products":["ACCOUNTS"]}
                """.formatted(ITEM), MediaType.APPLICATION_JSON));

        var item = client.getItem(ITEM).orElseThrow();

        assertThat(item.status()).isEqualTo("LOGIN_ERROR");
        assertThat(item.clientUserId()).isEqualTo("user-1");
        assertThat(item.connector().name()).isEqualTo("Inter");
        assertThat(item.error().message()).isEqualTo("Consentimento expirado");
        assertThat(item.lastUpdatedAt()).isEqualTo(Instant.parse("2026-10-30T10:00:00Z"));
    }

    @Test
    void serverError_becomesBankProviderException() {
        expectAuth("key-1");
        server.expect(requestTo(BASE + "/accounts?itemId=" + ITEM)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.listAccounts(ITEM)).isInstanceOf(BankProviderException.class);
    }

    @Test
    void deleteItem_alreadyGone_isFine() {
        expectAuth("key-1");
        server.expect(requestTo(BASE + "/items/" + ITEM)).andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        client.deleteItem(ITEM);
        server.verify();
    }

    @Test
    void authBody_neverPrintsTheSecret() {
        // O RestClient em DEBUG loga o corpo pelo toString.
        assertThat(new PluggyClient.AuthRequest("client-id", "client-secret").toString())
                .contains("client-id").doesNotContain("client-secret");
    }

    private void expectAuth(String apiKey) {
        server.expect(once(), requestTo(BASE + "/auth"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"clientId\":\"client-id\",\"clientSecret\":\"client-secret\"}"))
                .andRespond(withSuccess("{\"apiKey\":\"" + apiKey + "\"}", MediaType.APPLICATION_JSON));
    }

    private void expectAccounts(String apiKey) {
        server.expect(requestTo(BASE + "/accounts?itemId=" + ITEM))
                .andExpect(header("X-API-KEY", apiKey))
                .andRespond(withSuccess("""
                        {"total":2,"totalPages":1,"page":1,"results":[
                          {"id":"a1","type":"BANK","subtype":"CHECKING_ACCOUNT","name":"Conta Corrente","number":"1234567-8"},
                          {"id":"a2","type":"CREDIT","subtype":"CREDIT_CARD","name":"Mastercard","marketingName":"Inter Black","number":"1234"}]}
                        """, MediaType.APPLICATION_JSON));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
