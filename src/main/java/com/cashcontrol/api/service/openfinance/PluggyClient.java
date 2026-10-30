package com.cashcontrol.api.service.openfinance;

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.exception.BankProviderException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * API da Pluggy (Open Finance). É o único lugar que conhece formato e regras dela.
 *
 * <p>Regras da API que moldam o uso:
 * <ul>
 *   <li>{@code POST /auth} troca clientId/clientSecret por uma {@code apiKey} de 2 horas, mandada
 *       no cabeçalho {@code X-API-KEY}. Ela fica em memória e é renovada com folga; se mesmo assim
 *       a Pluggy recusar (401/403), a chave é descartada e a chamada repetida uma vez;</li>
 *   <li>o connect token vale 30 minutos e só abre o widget; o item criado nele carrega o
 *       {@code clientUserId}, que é como se sabe de quem é cada conexão.</li>
 * </ul>
 */
@Component
public class PluggyClient {

    private static final Duration API_KEY_TTL = Duration.ofHours(2);
    private static final Duration API_KEY_MARGIN = Duration.ofMinutes(5);
    /** Páginas de 500: 20 cobrem com folga os 12 meses de uma conta pessoal. */
    private static final int MAX_TRANSACTION_PAGES = 20;

    private final RestClient restClient;
    private final AppProperties appProperties;
    private final Clock clock;

    private ApiKey apiKey;

    @Autowired
    public PluggyClient(RestClient pluggyRestClient, AppProperties appProperties) {
        this(pluggyRestClient, appProperties, Clock.systemUTC());
    }

    public PluggyClient(RestClient pluggyRestClient, AppProperties appProperties, Clock clock) {
        this.restClient = pluggyRestClient;
        this.appProperties = appProperties;
        this.clock = clock;
    }

    /**
     * Token para o widget. Com {@code itemId}, o widget atualiza aquele item (renovar o
     * consentimento) em vez de criar outro.
     */
    public String createConnectToken(String clientUserId, String itemId) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("clientUserId", clientUserId);
        options.put("avoidDuplicates", true);
        String webhookUrl = appProperties.getPluggy().getWebhookUrl();
        if (webhookUrl != null && !webhookUrl.isBlank()) {
            options.put("webhookUrl", webhookUrl);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (itemId != null) {
            body.put("itemId", itemId);
        }
        body.put("options", options);
        ConnectToken token = call(key -> restClient.post().uri(url("/connect_token"))
                .header("X-API-KEY", key).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(ConnectToken.class));
        return token.accessToken();
    }

    /** O item, ou vazio quando a Pluggy não o conhece (apagado, ou de outro cliente). */
    public Optional<PluggyItem> getItem(String itemId) {
        try {
            return Optional.ofNullable(call(key -> restClient.get().uri(url("/items/{id}"), itemId)
                    .header("X-API-KEY", key).retrieve().body(PluggyItem.class)));
        } catch (BankProviderException e) {
            if (e.getCause() instanceof HttpClientErrorException.NotFound) {
                return Optional.empty();
            }
            throw e;
        }
    }

    public List<PluggyAccount> listAccounts(String itemId) {
        AccountPage page = call(key -> restClient.get().uri(url("/accounts?itemId={id}"), itemId)
                .header("X-API-KEY", key).retrieve().body(AccountPage.class));
        return page == null || page.results() == null ? List.of() : page.results();
    }

    /**
     * Todas as transações da conta que a Pluggy tem (o Open Finance entrega cerca de 12 meses).
     * Sem filtro de data de propósito: lançamento que liquida depois chega com data retroativa,
     * e quem corta é o import, pela data do último extrato importado.
     */
    public List<PluggyTransaction> listTransactions(String accountId) {
        List<PluggyTransaction> all = new ArrayList<>();
        String after = null;
        for (int page = 0; page < MAX_TRANSACTION_PAGES; page++) {
            URI uri = UriComponentsBuilder.fromUriString(url("/v2/transactions"))
                    .queryParam("accountId", accountId)
                    .queryParamIfPresent("after", Optional.ofNullable(after))
                    .encode().build().toUri();
            TransactionPage result = call(key -> restClient.get().uri(uri)
                    .header("X-API-KEY", key).retrieve().body(TransactionPage.class));
            if (result == null || result.results() == null) {
                break;
            }
            all.addAll(result.results());
            after = result.next();
            if (after == null || after.isBlank()) {
                return all;
            }
        }
        if (after != null && !after.isBlank()) {
            throw new BankProviderException("O banco devolveu lançamentos demais de uma vez.", null);
        }
        return all;
    }

    /** Faturas fechadas do cartão (Open Finance traz cerca de 12), sem os lançamentos. */
    public List<PluggyBill> listBills(String accountId) {
        BillPage page = call(key -> restClient.get().uri(url("/bills?accountId={id}"), accountId)
                .header("X-API-KEY", key).retrieve().body(BillPage.class));
        return page == null || page.results() == null ? List.of() : page.results();
    }

    /** Revoga o item na Pluggy. Já apagado conta como sucesso. */
    public void deleteItem(String itemId) {
        try {
            call(key -> restClient.delete().uri(url("/items/{id}"), itemId)
                    .header("X-API-KEY", key).retrieve().toBodilessEntity());
        } catch (BankProviderException e) {
            if (!(e.getCause() instanceof HttpClientErrorException.NotFound)) {
                throw e;
            }
        }
    }

    private <T> T call(Function<String, T> request) {
        try {
            try {
                return request.apply(apiKey());
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode() != HttpStatus.UNAUTHORIZED && e.getStatusCode() != HttpStatus.FORBIDDEN) {
                    throw e;
                }
                invalidateApiKey();
                return request.apply(apiKey());
            }
        } catch (RestClientException e) {
            throw new BankProviderException("Não foi possível falar com o banco agora. Tente novamente em instantes.", e);
        }
    }

    private synchronized String apiKey() {
        Instant now = clock.instant();
        if (apiKey == null || !now.isBefore(apiKey.renewAt())) {
            AuthResponse response = restClient.post().uri(url("/auth"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new AuthRequest(appProperties.getPluggy().getClientId(), appProperties.getPluggy().getClientSecret()))
                    .retrieve().body(AuthResponse.class);
            if (response == null || response.apiKey() == null) {
                throw new RestClientException("Pluggy /auth answered without apiKey");
            }
            apiKey = new ApiKey(response.apiKey(), now.plus(API_KEY_TTL).minus(API_KEY_MARGIN));
        }
        return apiKey.value();
    }

    private synchronized void invalidateApiKey() {
        apiKey = null;
    }

    private String url(String path) {
        return appProperties.getPluggy().getBaseUrl() + path;
    }

    private record ApiKey(String value, Instant renewAt) {
    }

    /**
     * Em DEBUG o RestClient loga o corpo de cada requisição pelo {@code toString}; sem este
     * override o secret iria parar no log.
     */
    public record AuthRequest(String clientId, String clientSecret) {
        @Override
        public String toString() {
            return "AuthRequest[clientId=" + clientId + ", clientSecret=***]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuthResponse(String apiKey) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ConnectToken(String accessToken) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountPage(List<PluggyAccount> results) {
    }

    /** Campos do item que o app usa. {@code status}: UPDATED, UPDATING, LOGIN_ERROR, OUTDATED... */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PluggyItem(String id, String status, String executionStatus, String clientUserId,
                             Instant lastUpdatedAt, Connector connector, ItemError error) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Connector(String name) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record ItemError(String code, String message) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransactionPage(List<PluggyTransaction> results, String next) {
    }

    /**
     * Lançamento como a Pluggy devolve. Na conta corrente {@code amount} tem sinal (débito
     * negativo) e {@code description} é "Histórico - Descrição" do extrato do Inter; no cartão
     * o sinal não é confiável (compra positiva, pagamento negativo) e vale o {@code type}.
     * {@code status}: POSTED ou PENDING. {@code type}: DEBIT ou CREDIT.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PluggyTransaction(String id, String description, BigDecimal amount, String type, String status,
                                    Instant date, CreditCardMetadata creditCardMetadata) {

        public PluggyTransaction(String id, String description, BigDecimal amount, String type, String status,
                                 Instant date) {
            this(id, description, amount, type, status, date, null);
        }
    }

    /**
     * Só em lançamento de cartão. {@code cardNumber}: final do cartão físico ou do virtual.
     * {@code billId} só depois do fechamento. Na parcela, {@code purchaseDate} é a data da compra
     * (meia-noite UTC); {@code date} do lançamento é a da parcela na fatura.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreditCardMetadata(String cardNumber, String billId, Integer installmentNumber,
                                     Integer totalInstallments, Instant purchaseDate) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BillPage(List<PluggyBill> results) {
    }

    /** Fatura fechada. {@code dueDate} vem à meia-noite UTC. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PluggyBill(String id, Instant dueDate, BigDecimal totalAmount) {
    }

    /** {@code type}: BANK ou CREDIT. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PluggyAccount(String id, String type, String subtype, String name, String marketingName, String number) {
    }
}
