package com.cashcontrol.api.service.push;

/**
 * @author Andre
 * 30 de Outubro. de 2026
 */

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class WebPushCryptoTest {

    private static final Base64.Decoder B64 = Base64.getUrlDecoder();

    /** Exemplo da seção 5 do RFC 8291, com as chaves e o sal do apêndice A. */
    @Test
    void encrypt_matchesTheRfc8291Example() throws Exception {
        KeyPair server = new KeyPair(
                WebPushCrypto.decodePublic(B64.decode("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8")),
                WebPushCrypto.decodePrivate(B64.decode("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw")));
        byte[] body = WebPushCrypto.encrypt(
                "When I grow up, I want to be a watermelon".getBytes(StandardCharsets.US_ASCII),
                WebPushCrypto.decodePublic(B64.decode("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4")),
                B64.decode("BTBZMqHH6r4Tts7J_aSIgg"), server, B64.decode("DGv6ra1nlYgDCS1FRnbzlw"));

        assertThat(Base64.getUrlEncoder().withoutPadding().encodeToString(body)).isEqualTo(
                "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml"
                + "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT"
                + "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN");
    }

    @Test
    void publicKey_roundTripsThroughTheRawFormat() throws Exception {
        KeyPair pair = WebPushCrypto.newKeyPair();
        byte[] raw = WebPushCrypto.encodePublic((java.security.interfaces.ECPublicKey) pair.getPublic());

        assertThat(raw).hasSize(65);
        assertThat(WebPushCrypto.decodePublic(raw)).isEqualTo(pair.getPublic());
    }
}
