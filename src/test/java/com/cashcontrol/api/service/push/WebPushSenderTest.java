package com.cashcontrol.api.service.push;

/**
 * @author Andre
 * 30 de Outubro. de 2026
 */

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.entity.PushSubscription;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WebPushSenderTest {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(201);
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> encoding = new AtomicReference<>();
    private KeyPair vapid;
    private WebPushSender sender;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/push", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            encoding.set(exchange.getRequestHeaders().getFirst("Content-Encoding"));
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();

        vapid = WebPushCrypto.newKeyPair();
        AppProperties properties = new AppProperties();
        properties.getPush().setVapidPublicKey(B64.encodeToString(WebPushCrypto.encodePublic((ECPublicKey) vapid.getPublic())));
        properties.getPush().setVapidPrivateKey(B64.encodeToString(unsigned32(((ECPrivateKey) vapid.getPrivate()).getS())));
        properties.getPush().setSubject("mailto:test@example.com");
        sender = new WebPushSender(properties);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void send_signsVapidForTheOriginOfTheEndpoint() throws Exception {
        assertThat(sender.send(subscription(), "{}")).isEqualTo(WebPushSender.Result.DELIVERED);

        assertThat(encoding.get()).isEqualTo("aes128gcm");
        String header = authorization.get();
        assertThat(header).startsWith("vapid t=").contains(", k=");
        String jwt = header.substring("vapid t=".length(), header.indexOf(", k="));
        String[] parts = jwt.split("\\.");
        String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(claims).contains("\"aud\":\"http://127.0.0.1:" + server.getAddress().getPort() + "\"")
                .contains("\"sub\":\"mailto:test@example.com\"");

        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(vapid.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    @Test
    void send_goneSubscription_isReportedSoItCanBeDeleted() throws Exception {
        status.set(410);
        assertThat(sender.send(subscription(), "{}")).isEqualTo(WebPushSender.Result.GONE);

        status.set(500);
        assertThat(sender.send(subscription(), "{}")).isEqualTo(WebPushSender.Result.FAILED);
    }

    private PushSubscription subscription() throws Exception {
        KeyPair device = WebPushCrypto.newKeyPair();
        PushSubscription subscription = new PushSubscription();
        subscription.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/push/abc");
        subscription.setP256dh(B64.encodeToString(WebPushCrypto.encodePublic((ECPublicKey) device.getPublic())));
        subscription.setAuth(B64.encodeToString(new byte[16]));
        return subscription;
    }

    private static byte[] unsigned32(java.math.BigInteger value) {
        byte[] bytes = value.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(bytes.length, 32);
        System.arraycopy(bytes, bytes.length - copy, out, 32 - copy, copy);
        return out;
    }
}
