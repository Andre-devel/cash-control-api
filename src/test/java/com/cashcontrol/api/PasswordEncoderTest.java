package com.cashcontrol.api;

import com.cashcontrol.api.config.PasswordEncoderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordEncoderTest {

    private final PasswordEncoder encoder = new PasswordEncoderConfig().passwordEncoder();

    @Test
    void encodedHashStartsWithArgon2idPrefix() {
        String hash = encoder.encode("TestPassword123!");
        assertThat(hash).startsWith("$argon2id$");
    }

    @Test
    void matchesReturnsTrueForCorrectPassword() {
        String password = "CorrectPassword1!";
        String hash = encoder.encode(password);
        assertThat(encoder.matches(password, hash)).isTrue();
    }

    @Test
    void matchesReturnsFalseForWrongPassword() {
        String hash = encoder.encode("CorrectPassword1!");
        assertThat(encoder.matches("WrongPassword2@", hash)).isFalse();
    }

    @Test
    void differentSaltProducesDifferentHashes() {
        String password = "SamePassword1!";
        String hash1 = encoder.encode(password);
        String hash2 = encoder.encode(password);
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void bothHashesStillMatchOriginalPassword() {
        String password = "SamePassword1!";
        String hash1 = encoder.encode(password);
        String hash2 = encoder.encode(password);
        assertThat(encoder.matches(password, hash1)).isTrue();
        assertThat(encoder.matches(password, hash2)).isTrue();
    }
}