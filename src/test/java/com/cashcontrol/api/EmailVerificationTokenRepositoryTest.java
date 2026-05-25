package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountStatus;
import com.cashcontrol.api.domain.entity.AuthOrigin;
import com.cashcontrol.api.domain.entity.EmailVerificationToken;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.repository.AccountStatusRepository;
import com.cashcontrol.api.repository.AuthOriginRepository;
import com.cashcontrol.api.repository.EmailVerificationTokenRepository;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class EmailVerificationTokenRepositoryTest {

    @Autowired private EmailVerificationTokenRepository tokenRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountStatusRepository accountStatusRepository;
    @Autowired private AuthOriginRepository authOriginRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        AccountStatus active = accountStatusRepository.findBySlug("ACTIVE").orElseThrow();
        AuthOrigin local = authOriginRepository.findBySlug("LOCAL").orElseThrow();
        testUser = userRepository.save(buildUser(active, local));
    }

    private User buildUser(AccountStatus status, AuthOrigin origin) {
        User user = new User();
        user.setEmail("evtoken-" + System.nanoTime() + "@example.com");
        user.setAccountStatus(status);
        user.setAuthOrigin(origin);
        user.setCredentialsUpdatedAt(Instant.now());
        return user;
    }

    private EmailVerificationToken buildToken(User user, String hash, Instant expiresAt) {
        EmailVerificationToken token = new EmailVerificationToken();
        token.setUser(user);
        token.setTokenHash(hash);
        token.setExpiresAt(expiresAt);
        return token;
    }

    @Test
    void findByTokenHash_returnsActiveToken() {
        tokenRepository.save(buildToken(testUser, "active-ev-hash-001",
                Instant.now().plus(24, ChronoUnit.HOURS)));

        Optional<EmailVerificationToken> found =
                tokenRepository.findByTokenHashAndConsumedAtIsNullAndInvalidatedAtIsNull("active-ev-hash-001");

        assertThat(found).isPresent();
        assertThat(found.get().getConsumedAt()).isNull();
        assertThat(found.get().getInvalidatedAt()).isNull();
    }

    @Test
    void findByTokenHash_returnsEmptyForConsumedToken() {
        EmailVerificationToken token = buildToken(testUser, "consumed-ev-hash-002",
                Instant.now().plus(24, ChronoUnit.HOURS));
        token.setConsumedAt(Instant.now());
        tokenRepository.save(token);

        Optional<EmailVerificationToken> found =
                tokenRepository.findByTokenHashAndConsumedAtIsNullAndInvalidatedAtIsNull("consumed-ev-hash-002");

        assertThat(found).isEmpty();
    }

    @Test
    void findByTokenHash_returnsEmptyForInvalidatedToken() {
        EmailVerificationToken token = buildToken(testUser, "invalidated-ev-hash-003",
                Instant.now().plus(24, ChronoUnit.HOURS));
        token.setInvalidatedAt(Instant.now());
        tokenRepository.save(token);

        Optional<EmailVerificationToken> found =
                tokenRepository.findByTokenHashAndConsumedAtIsNullAndInvalidatedAtIsNull("invalidated-ev-hash-003");

        assertThat(found).isEmpty();
    }

    @Test
    void findByUserId_returnsListOfActiveTokens() {
        tokenRepository.save(buildToken(testUser, "user-active-hash-001",
                Instant.now().plus(24, ChronoUnit.HOURS)));
        tokenRepository.save(buildToken(testUser, "user-active-hash-002",
                Instant.now().plus(24, ChronoUnit.HOURS)));

        EmailVerificationToken consumed = buildToken(testUser, "user-consumed-hash-003",
                Instant.now().plus(24, ChronoUnit.HOURS));
        consumed.setConsumedAt(Instant.now());
        tokenRepository.save(consumed);

        List<EmailVerificationToken> active =
                tokenRepository.findByUserIdAndConsumedAtIsNullAndInvalidatedAtIsNull(testUser.getId());

        assertThat(active).hasSize(2);
        assertThat(active).allMatch(t -> t.getConsumedAt() == null && t.getInvalidatedAt() == null);
    }

    @Test
    void invalidateActiveTokensForUser_setsInvalidatedAtOnAllActiveTokens() {
        tokenRepository.save(buildToken(testUser, "to-invalidate-ev-001",
                Instant.now().plus(24, ChronoUnit.HOURS)));
        tokenRepository.save(buildToken(testUser, "to-invalidate-ev-002",
                Instant.now().plus(24, ChronoUnit.HOURS)));

        int count = tokenRepository.invalidateActiveTokensForUser(testUser.getId());
        assertThat(count).isEqualTo(2);

        List<EmailVerificationToken> active =
                tokenRepository.findByUserIdAndConsumedAtIsNullAndInvalidatedAtIsNull(testUser.getId());
        assertThat(active).isEmpty();
    }

    @Test
    void deleteConsumedOrInvalidatedBefore_deletesOldConsumedTokens() {
        EmailVerificationToken old = buildToken(testUser, "old-consumed-ev-hash",
                Instant.now().minus(10, ChronoUnit.DAYS));
        old.setConsumedAt(Instant.now().minus(10, ChronoUnit.DAYS));
        tokenRepository.save(old);

        Instant cutoff = Instant.now().minus(1, ChronoUnit.DAYS);
        int deleted = tokenRepository.deleteConsumedOrInvalidatedBefore(cutoff);

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(tokenRepository.findByTokenHashAndConsumedAtIsNullAndInvalidatedAtIsNull("old-consumed-ev-hash"))
                .isEmpty();
    }

    @Test
    void deleteConsumedOrInvalidatedBefore_keepsRecentTokens() {
        EmailVerificationToken recent = buildToken(testUser, "recent-consumed-ev-hash",
                Instant.now().plus(24, ChronoUnit.HOURS));
        recent.setConsumedAt(Instant.now());
        tokenRepository.save(recent);

        Instant cutoff = Instant.now().minus(1, ChronoUnit.DAYS);
        tokenRepository.deleteConsumedOrInvalidatedBefore(cutoff);

        // The recently consumed token should still be present (its createdAt is within the cutoff window)
        assertThat(tokenRepository.findAll()).anyMatch(t -> "recent-consumed-ev-hash".equals(t.getTokenHash()));
    }
}
