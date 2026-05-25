package com.cashcontrol.api;

import com.cashcontrol.api.util.DataMasker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DataMaskerTest {

    private final DataMasker masker = new DataMasker();

    // maskEmail

    @Test
    void maskEmail_standardAddress() {
        assertThat(masker.maskEmail("user@example.com")).isEqualTo("u***@example.com");
    }

    @Test
    void maskEmail_shortLocalPart() {
        assertThat(masker.maskEmail("ab@x.co")).isEqualTo("a***@x.co");
    }

    @Test
    void maskEmail_singleCharLocalPart() {
        assertThat(masker.maskEmail("a@domain.org")).isEqualTo("a***@domain.org");
    }

    @Test
    void maskEmail_longLocalPart() {
        assertThat(masker.maskEmail("verylongname@company.io")).isEqualTo("v***@company.io");
    }

    @Test
    void maskEmail_nullInput() {
        assertThat(masker.maskEmail(null)).isNull();
    }

    @Test
    void maskEmail_noAtSign() {
        assertThat(masker.maskEmail("notanemail")).isEqualTo("***");
    }

    @Test
    void maskEmail_atSignAtStart() {
        assertThat(masker.maskEmail("@domain.com")).isEqualTo("***");
    }

    // maskIpV4

    @Test
    void maskIpV4_standard() {
        assertThat(masker.maskIpV4("192.168.1.100")).isEqualTo("192.168.1.0");
    }

    @Test
    void maskIpV4_zeroLastOctet() {
        assertThat(masker.maskIpV4("10.0.0.255")).isEqualTo("10.0.0.0");
    }

    @Test
    void maskIpV4_nullInput() {
        assertThat(masker.maskIpV4(null)).isNull();
    }

    @Test
    void maskIpV4_noDot() {
        assertThat(masker.maskIpV4("localhost")).isEqualTo("localhost");
    }

    // maskIpV6

    @Test
    void maskIpV6_fullAddress() {
        String masked = masker.maskIpV6("2001:0db8:85a3:0000:0000:8a2e:0370:7334");
        // Last 5 of 8 groups should be zeroed
        assertThat(masked).startsWith("2001:0db8:85a3");
        assertThat(masked).endsWith(":0:0:0:0:0");
    }

    @Test
    void maskIpV6_nullInput() {
        assertThat(masker.maskIpV6(null)).isNull();
    }

    // maskIp (auto-detect)

    @Test
    void maskIp_ipv4() {
        assertThat(masker.maskIp("203.0.113.45")).isEqualTo("203.0.113.0");
    }

    @Test
    void maskIp_ipv6() {
        String result = masker.maskIp("2001:db8::1");
        assertThat(result).isNotNull();
    }

    @Test
    void maskIp_nullInput() {
        assertThat(masker.maskIp(null)).isNull();
    }

    // truncateUserAgent

    @Test
    void truncateUserAgent_withinLimit() {
        String ua = "Mozilla/5.0";
        assertThat(masker.truncateUserAgent(ua, 512)).isEqualTo(ua);
    }

    @Test
    void truncateUserAgent_exceedsLimit() {
        String ua = "A".repeat(600);
        assertThat(masker.truncateUserAgent(ua, 512)).hasSize(512);
    }

    @Test
    void truncateUserAgent_exactLimit() {
        String ua = "B".repeat(512);
        assertThat(masker.truncateUserAgent(ua, 512)).isEqualTo(ua);
    }

    @Test
    void truncateUserAgent_nullInput() {
        assertThat(masker.truncateUserAgent(null, 512)).isNull();
    }

    @Test
    void truncateUserAgent_emptyString() {
        assertThat(masker.truncateUserAgent("", 512)).isEmpty();
    }

    // sanitizeTokenValue

    @Test
    void sanitizeTokenValue_alwaysRedacted() {
        assertThat(masker.sanitizeTokenValue("any-token-value")).isEqualTo("[REDACTED]");
    }

    @Test
    void sanitizeTokenValue_nullInput() {
        assertThat(masker.sanitizeTokenValue(null)).isEqualTo("[REDACTED]");
    }
}