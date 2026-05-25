package com.cashcontrol.api;

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.security.RateLimitingFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class RateLimitingFilterTest {

    @LocalServerPort
    private int port;

    @Autowired private RateLimitingFilter rateLimitingFilter;
    @Autowired private AppProperties appProperties;

    private final RestTemplate restTemplate = buildRestTemplate();

    private static final String VALID_LOGIN_BODY = "{\"email\":\"x@x.com\",\"password\":\"pass\"}";
    private static final String VALID_REGISTER_BODY = "{\"email\":\"x@x.com\",\"password\":\"pass\",\"consentAccepted\":true}";

    private static RestTemplate buildRestTemplate() {
        RestTemplate rt = new RestTemplate();
        // Don't throw on 4xx/5xx — just return the response so tests can assert on status codes
        rt.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response)
                    throws java.io.IOException {
                return false;
            }
        });
        return rt;
    }

    @BeforeEach
    void setUp() {
        rateLimitingFilter.clearBucketsForTesting();
    }

    private ResponseEntity<String> doPost(String path, String body, String ip) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (ip != null) {
            headers.set("X-Forwarded-For", ip);
        }
        return restTemplate.exchange(
                "http://localhost:" + port + path,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class);
    }

    private ResponseEntity<String> doGet(String path, String ip) {
        HttpHeaders headers = new HttpHeaders();
        if (ip != null) {
            headers.set("X-Forwarded-For", ip);
        }
        return restTemplate.exchange(
                "http://localhost:" + port + path,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
    }

    @Test
    void exhaustedBucket_returns429WithRetryAfterHeaderAndCorrectBody() {
        rateLimitingFilter.forceExhaustBucketForTesting("20.20.20.20");

        ResponseEntity<String> response = doPost("/api/v1/auth/login", VALID_LOGIN_BODY, "20.20.20.20");

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst("Retry-After")).isNotNull();
        assertThat(response.getBody()).contains("RATE_LIMITED");
        assertThat(response.getBody()).contains("Too many requests.");
        assertThat(response.getBody()).contains("correlationId");
    }

    @Test
    void differentIps_haveIndependentBuckets() {
        // Exhaust IP-A
        rateLimitingFilter.forceExhaustBucketForTesting("21.21.21.21");

        // IP-A is rate limited
        ResponseEntity<String> rateLimitedResponse = doPost("/api/v1/auth/login", VALID_LOGIN_BODY, "21.21.21.21");
        assertThat(rateLimitedResponse.getStatusCode().value()).isEqualTo(429);

        // IP-B has an independent bucket and is NOT rate limited
        ResponseEntity<String> allowedResponse = doPost("/api/v1/auth/login", VALID_LOGIN_BODY, "21.21.21.22");
        assertThat(allowedResponse.getStatusCode().value()).isNotEqualTo(429);
    }

    @Test
    void afterWindowExpires_expiredEntries_areEvictedAndRequestSucceeds() {
        // Fill bucket with timestamps older than the configured window (expired)
        rateLimitingFilter.forceExhaustWithExpiredTimestampsForTesting("22.22.22.22");

        // Expired entries evicted on tryAcquire → request allowed
        ResponseEntity<String> response = doPost("/api/v1/auth/login", VALID_LOGIN_BODY, "22.22.22.22");
        assertThat(response.getStatusCode().value()).isNotEqualTo(429);
    }

    @Test
    void afterBucketReset_requestsSucceedAgain() {
        rateLimitingFilter.forceExhaustBucketForTesting("23.23.23.23");

        // Rate limited
        assertThat(doPost("/api/v1/auth/login", VALID_LOGIN_BODY, "23.23.23.23")
                .getStatusCode().value()).isEqualTo(429);

        // After clearing buckets (simulates window expiry), requests succeed again
        rateLimitingFilter.clearBucketsForTesting();

        assertThat(doPost("/api/v1/auth/login", VALID_LOGIN_BODY, "23.23.23.23")
                .getStatusCode().value()).isNotEqualTo(429);
    }

    @Test
    void nPlusOneRequests_exceedsLimit_returns429() {
        int maxRequests = appProperties.getSecurity().getRateLimitRequestsPerMinute();

        // Pre-fill to maxRequests - 1 (one slot left)
        rateLimitingFilter.forcePartialFillBucketForTesting("24.24.24.24", maxRequests - 1);

        // The Nth request (last allowed) succeeds
        assertThat(doPost("/api/v1/auth/login", VALID_LOGIN_BODY, "24.24.24.24")
                .getStatusCode().value()).isNotEqualTo(429);

        // N+1th request exceeds the limit → 429
        assertThat(doPost("/api/v1/auth/login", VALID_LOGIN_BODY, "24.24.24.24")
                .getStatusCode().value()).isEqualTo(429);
    }

    @Test
    void rateLimiting_doesNotApplyToNonAuthEndpoints() {
        rateLimitingFilter.forceExhaustBucketForTesting("25.25.25.25");

        // /actuator/health is not in the rate-limited path list
        ResponseEntity<String> response = doGet("/actuator/health", "25.25.25.25");
        assertThat(response.getStatusCode().value()).isNotEqualTo(429);
    }

    @Test
    void rateLimitingAppliesTo_registerEndpoint() {
        rateLimitingFilter.forceExhaustBucketForTesting("26.26.26.26");

        ResponseEntity<String> response = doPost("/api/v1/auth/register", VALID_REGISTER_BODY, "26.26.26.26");
        assertThat(response.getStatusCode().value()).isEqualTo(429);
    }

    @Test
    void retryAfterHeader_containsPositiveValue() {
        rateLimitingFilter.forceExhaustBucketForTesting("27.27.27.27");

        ResponseEntity<String> response = doPost("/api/v1/auth/login", VALID_LOGIN_BODY, "27.27.27.27");
        assertThat(response.getStatusCode().value()).isEqualTo(429);

        String retryAfter = response.getHeaders().getFirst("Retry-After");
        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isGreaterThan(0);
    }
}
