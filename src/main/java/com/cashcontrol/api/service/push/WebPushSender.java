package com.cashcontrol.api.service.push;

/**
 * @author Andre
 * 30 de Outubro. de 2026
 */

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.entity.PushSubscription;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

/**
 * Entrega uma mensagem a um aparelho pelo serviço de push do navegador (no Chrome Android, o
 * FCM), autenticada por VAPID (RFC 8292) e cifrada só para ele (RFC 8291).
 */
@Slf4j
@Component
public class WebPushSender {

    public enum Result { DELIVERED, GONE, FAILED }

    /** Quanto tempo o serviço de push guarda a mensagem com o aparelho desligado. */
    private static final Duration TTL = Duration.ofDays(1);
    private static final Duration JWT_LIFETIME = Duration.ofHours(12);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DECODER = Base64.getUrlDecoder();

    private final AppProperties appProperties;
    private final Clock clock = Clock.systemUTC();
    private final SecureRandom random = new SecureRandom();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public WebPushSender(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public Result send(PushSubscription subscription, String payload) {
        try {
            byte[] salt = new byte[16];
            random.nextBytes(salt);
            byte[] body = WebPushCrypto.encrypt(payload.getBytes(StandardCharsets.UTF_8),
                    WebPushCrypto.decodePublic(B64_DECODER.decode(subscription.getP256dh())),
                    B64_DECODER.decode(subscription.getAuth()), WebPushCrypto.newKeyPair(), salt);
            URI endpoint = URI.create(subscription.getEndpoint());
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(10))
                    .header("TTL", String.valueOf(TTL.toSeconds()))
                    .header("Urgency", "normal")
                    .header("Content-Encoding", "aes128gcm")
                    .header("Content-Type", "application/octet-stream")
                    .header("Authorization", "vapid t=" + vapidJwt(endpoint) + ", k="
                                             + appProperties.getPush().getVapidPublicKey())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 404 || status == 410) {
                return Result.GONE;
            }
            if (status / 100 != 2) {
                log.warn("Web push refused: host={} status={} body={}", endpoint.getHost(), status,
                        abbreviate(response.body()));
                return Result.FAILED;
            }
            return Result.DELIVERED;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            log.warn("Web push: invalid subscription {}: {}", subscription.getId(), e.toString());
            return Result.GONE;
        } catch (IOException e) {
            log.warn("Web push: delivery failed for {}: {}", subscription.getId(), e.toString());
            return Result.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.FAILED;
        }
    }

    /** JWT ES256 com a origem do serviço de push como audiência. */
    private String vapidJwt(URI endpoint) throws GeneralSecurityException {
        String audience = endpoint.getScheme() + "://" + endpoint.getRawAuthority();
        long expiresAt = clock.instant().plus(JWT_LIFETIME).getEpochSecond();
        String header = B64.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = B64.encodeToString(("{\"aud\":\"" + audience + "\",\"exp\":" + expiresAt + ",\"sub\":\""
                                            + appProperties.getPush().getSubject() + "\"}").getBytes(StandardCharsets.UTF_8));
        ECPrivateKey key = WebPushCrypto.decodePrivate(B64_DECODER.decode(appProperties.getPush().getVapidPrivateKey()));
        // P1363: r||s com 64 bytes, o formato do JWS (o padrão do JDK é DER).
        Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign(key);
        signer.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
        return header + "." + claims + "." + B64.encodeToString(signer.sign());
    }

    private static String abbreviate(String body) {
        return body == null ? null : body.substring(0, Math.min(body.length(), 200)).replaceAll("\\s+", " ");
    }
}
