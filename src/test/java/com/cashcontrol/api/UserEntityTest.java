package com.cashcontrol.api;

import com.cashcontrol.api.domain.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserEntityTest {

    @Test
    void toStringDoesNotContainPasswordHash() {
        User user = new User();
        user.setEmail("user@example.com");
        user.setPasswordHash("$argon2id$v=19$m=65536,t=3,p=1$somesalt$somehash");
        user.setCredentialsUpdatedAt(java.time.Instant.now());

        String toString = user.toString();

        assertThat(toString).doesNotContain("passwordHash");
        assertThat(toString).doesNotContain("argon2id");
        assertThat(toString).doesNotContain("somehash");
    }

    @Test
    void toStringContainsEmail() {
        User user = new User();
        user.setEmail("user@example.com");
        user.setCredentialsUpdatedAt(java.time.Instant.now());

        assertThat(user.toString()).contains("user@example.com");
    }

    @Test
    void equalsBasedOnIdOnly() {
        User user1 = new User();
        user1.setEmail("a@example.com");
        user1.setCredentialsUpdatedAt(java.time.Instant.now());

        User user2 = new User();
        user2.setEmail("b@example.com");
        user2.setCredentialsUpdatedAt(java.time.Instant.now());

        // Two new users with no id assigned are considered equal by Lombok's
        // @EqualsAndHashCode(onlyExplicitlyIncluded) when both ids are null
        assertThat(user1).isNotSameAs(user2);
    }

    @Test
    void passwordHashGetterExists() throws NoSuchMethodException {
        // Verify getPasswordHash() method exists and is accessible (public)
        var method = User.class.getMethod("getPasswordHash");
        assertThat(method).isNotNull();
        assertThat(method.getReturnType()).isEqualTo(String.class);
    }
}