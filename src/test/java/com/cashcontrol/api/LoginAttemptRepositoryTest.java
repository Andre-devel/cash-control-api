package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountStatus;
import com.cashcontrol.api.domain.entity.AuthOrigin;
import com.cashcontrol.api.domain.entity.LoginAttempt;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.repository.AccountStatusRepository;
import com.cashcontrol.api.repository.AuthOriginRepository;
import com.cashcontrol.api.repository.LoginAttemptRepository;
import com.cashcontrol.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class LoginAttemptRepositoryTest {

    @Autowired private LoginAttemptRepository loginAttemptRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountStatusRepository accountStatusRepository;
    @Autowired private AuthOriginRepository authOriginRepository;

    private User testUser;
    private final String maskedIp = "192.168.1.0";

    @BeforeEach
    void setUp() {
        AccountStatus active = accountStatusRepository.findBySlug("ACTIVE").orElseThrow();
        AuthOrigin local = authOriginRepository.findBySlug("LOCAL").orElseThrow();

        User user = new User();
        user.setEmail("login-attempt-test-" + System.nanoTime() + "@example.com");
        user.setAccountStatus(active);
        user.setAuthOrigin(local);
        user.setCredentialsUpdatedAt(Instant.now());
        testUser = userRepository.save(user);
    }

    private LoginAttempt buildAttempt(UUID userId, String ip, boolean success, Instant attemptedAt) {
        LoginAttempt attempt = new LoginAttempt();
        attempt.setUserId(userId);
        attempt.setIpAddressMasked(ip);
        attempt.setWasSuccessful(success);
        attempt.setCorrelationId(UUID.randomUUID());
        attempt.setAttemptedAt(attemptedAt);
        return attempt;
    }

    @Test
    void countByUserIdAndWasSuccessfulFalseAndAttemptedAtAfterReturnsCorrectCount() {
        Instant now = Instant.now();
        Instant windowStart = now.minus(15, ChronoUnit.MINUTES);

        loginAttemptRepository.save(buildAttempt(testUser.getId(), maskedIp, false, now.minus(5, ChronoUnit.MINUTES)));
        loginAttemptRepository.save(buildAttempt(testUser.getId(), maskedIp, false, now.minus(10, ChronoUnit.MINUTES)));
        loginAttemptRepository.save(buildAttempt(testUser.getId(), maskedIp, true, now.minus(3, ChronoUnit.MINUTES)));
        // outside the window
        loginAttemptRepository.save(buildAttempt(testUser.getId(), maskedIp, false, now.minus(20, ChronoUnit.MINUTES)));

        int count = loginAttemptRepository.countByUserIdAndWasSuccessfulFalseAndAttemptedAtAfter(
                testUser.getId(), windowStart);

        assertThat(count).isEqualTo(2);
    }

    @Test
    void countByUserIdAndWasSuccessfulFalseExcludesSuccessfulAttempts() {
        Instant now = Instant.now();
        Instant windowStart = now.minus(15, ChronoUnit.MINUTES);

        loginAttemptRepository.save(buildAttempt(testUser.getId(), maskedIp, true, now.minus(5, ChronoUnit.MINUTES)));
        loginAttemptRepository.save(buildAttempt(testUser.getId(), maskedIp, true, now.minus(8, ChronoUnit.MINUTES)));

        int count = loginAttemptRepository.countByUserIdAndWasSuccessfulFalseAndAttemptedAtAfter(
                testUser.getId(), windowStart);

        assertThat(count).isZero();
    }

    @Test
    void countByIpAddressMaskedAndAttemptedAtAfterReturnsCorrectCount() {
        Instant now = Instant.now();
        Instant windowStart = now.minus(15, ChronoUnit.MINUTES);
        String differentIp = "10.0.0.0";

        loginAttemptRepository.save(buildAttempt(testUser.getId(), maskedIp, false, now.minus(5, ChronoUnit.MINUTES)));
        loginAttemptRepository.save(buildAttempt(testUser.getId(), maskedIp, true, now.minus(7, ChronoUnit.MINUTES)));
        loginAttemptRepository.save(buildAttempt(testUser.getId(), differentIp, false, now.minus(3, ChronoUnit.MINUTES)));

        int count = loginAttemptRepository.countByIpAddressMaskedAndAttemptedAtAfter(maskedIp, windowStart);

        assertThat(count).isEqualTo(2);
    }

    @Test
    void loginAttemptWithNullUserIdCanBeSaved() {
        LoginAttempt attempt = buildAttempt(null, maskedIp, false, Instant.now());

        LoginAttempt saved = loginAttemptRepository.save(attempt);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getUserId()).isNull();
    }

    @Test
    void countByUserIdReturnZeroForDifferentUser() {
        Instant now = Instant.now();
        Instant windowStart = now.minus(15, ChronoUnit.MINUTES);
        UUID differentUserId = UUID.randomUUID();

        loginAttemptRepository.save(buildAttempt(testUser.getId(), maskedIp, false, now.minus(5, ChronoUnit.MINUTES)));

        int count = loginAttemptRepository.countByUserIdAndWasSuccessfulFalseAndAttemptedAtAfter(
                differentUserId, windowStart);

        assertThat(count).isZero();
    }
}