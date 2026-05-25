package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountLockout;
import com.cashcontrol.api.domain.entity.AccountStatus;
import com.cashcontrol.api.domain.entity.AuthOrigin;
import com.cashcontrol.api.domain.entity.LockoutType;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.repository.AccountLockoutRepository;
import com.cashcontrol.api.repository.AccountStatusRepository;
import com.cashcontrol.api.repository.AuthOriginRepository;
import com.cashcontrol.api.repository.LockoutTypeRepository;
import com.cashcontrol.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class AccountLockoutRepositoryTest {

    @Autowired private AccountLockoutRepository accountLockoutRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountStatusRepository accountStatusRepository;
    @Autowired private AuthOriginRepository authOriginRepository;
    @Autowired private LockoutTypeRepository lockoutTypeRepository;

    private User testUser;
    private LockoutType manualLockoutType;

    @BeforeEach
    void setUp() {
        AccountStatus active = accountStatusRepository.findBySlug("ACTIVE").orElseThrow();
        AuthOrigin local = authOriginRepository.findBySlug("LOCAL").orElseThrow();
        manualLockoutType = lockoutTypeRepository.findBySlug("MANUAL").orElseThrow();

        User user = new User();
        user.setEmail("lockout-repo-" + System.nanoTime() + "@example.com");
        user.setAccountStatus(active);
        user.setAuthOrigin(local);
        user.setCredentialsUpdatedAt(Instant.now());
        testUser = userRepository.save(user);
    }

    @Test
    void findByUserIdAndUnlockedAtIsNull_returnsActiveLockout() {
        AccountLockout lockout = new AccountLockout();
        lockout.setUser(testUser);
        lockout.setLockoutType(manualLockoutType);
        lockout.setReason("Suspicious activity");
        accountLockoutRepository.save(lockout);

        Optional<AccountLockout> found =
                accountLockoutRepository.findByUserIdAndUnlockedAtIsNull(testUser.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getUnlockedAt()).isNull();
        assertThat(found.get().getReason()).isEqualTo("Suspicious activity");
    }

    @Test
    void findByUserIdAndUnlockedAtIsNull_returnsEmptyWhenUnlocked() {
        AccountLockout lockout = new AccountLockout();
        lockout.setUser(testUser);
        lockout.setLockoutType(manualLockoutType);
        lockout.setReason("Previously locked");
        lockout.setUnlockedAt(Instant.now());
        accountLockoutRepository.save(lockout);

        Optional<AccountLockout> found =
                accountLockoutRepository.findByUserIdAndUnlockedAtIsNull(testUser.getId());

        assertThat(found).isEmpty();
    }

    @Test
    void findByUserIdAndUnlockedAtIsNull_returnsEmptyWhenUserHasNoLockout() {
        Optional<AccountLockout> found =
                accountLockoutRepository.findByUserIdAndUnlockedAtIsNull(testUser.getId());

        assertThat(found).isEmpty();
    }
}
