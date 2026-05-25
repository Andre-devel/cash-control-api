package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountStatus;
import com.cashcontrol.api.domain.entity.AuthOrigin;
import com.cashcontrol.api.domain.entity.PasswordResetToken;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.repository.AccountStatusRepository;
import com.cashcontrol.api.repository.AuthOriginRepository;
import com.cashcontrol.api.repository.PasswordResetTokenRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class PasswordResetTokenRepositoryTest {

    @Autowired private PasswordResetTokenRepository tokenRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountStatusRepository accountStatusRepository;
    @Autowired private AuthOriginRepository authOriginRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        AccountStatus active = accountStatusRepository.findBySlug("ACTIVE").orElseThrow();
        AuthOrigin local = authOriginRepository.findBySlug("LOCAL").orElseThrow();

        User user = new User();
        user.setEmail("prt-test-" + System.nanoTime() + "@example.com");
        user.setAccountStatus(active);
        user.setAuthOrigin(local);
        user.setCredentialsUpdatedAt(Instant.now());
        testUser = userRepository.save(user);
    }

    private PasswordResetToken buildToken(String hash) {
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(testUser);
        token.setTokenHash(hash);
        token.setExpiresAt(Instant.now().plus(60, ChronoUnit.MINUTES));
        return token;
    }

    @Test
    void findByTokenHashActiveReturnsActiveToken() {
        tokenRepository.save(buildToken("active-hash-001"));

        Optional<PasswordResetToken> found =
                tokenRepository.findByTokenHashAndConsumedAtIsNullAndInvalidatedAtIsNull("active-hash-001");

        assertThat(found).isPresent();
    }

    @Test
    void findByTokenHashActiveDoesNotReturnConsumedToken() {
        PasswordResetToken token = buildToken("consumed-hash-002");
        token.setConsumedAt(Instant.now());
        tokenRepository.save(token);

        Optional<PasswordResetToken> found =
                tokenRepository.findByTokenHashAndConsumedAtIsNullAndInvalidatedAtIsNull("consumed-hash-002");

        assertThat(found).isEmpty();
    }

    @Test
    void findByTokenHashActiveDoesNotReturnInvalidatedToken() {
        PasswordResetToken token = buildToken("invalidated-hash-003");
        token.setInvalidatedAt(Instant.now());
        tokenRepository.save(token);

        Optional<PasswordResetToken> found =
                tokenRepository.findByTokenHashAndConsumedAtIsNullAndInvalidatedAtIsNull("invalidated-hash-003");

        assertThat(found).isEmpty();
    }

    @Test
    void invalidateActiveTokensForUserInvalidatesAllActiveTokens() {
        tokenRepository.save(buildToken("to-invalidate-001"));
        tokenRepository.save(buildToken("to-invalidate-002"));

        int count = tokenRepository.invalidateActiveTokensForUser(testUser.getId());
        assertThat(count).isEqualTo(2);

        assertThat(tokenRepository.findByTokenHashAndConsumedAtIsNullAndInvalidatedAtIsNull("to-invalidate-001"))
                .isEmpty();
        assertThat(tokenRepository.findByTokenHashAndConsumedAtIsNullAndInvalidatedAtIsNull("to-invalidate-002"))
                .isEmpty();
    }
}