package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.AccountStatus;
import com.cashcontrol.api.domain.entity.AuthOrigin;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.repository.AccountStatusRepository;
import com.cashcontrol.api.repository.AuthOriginRepository;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.security.AuthUserDetailsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class AuthUserDetailsServiceTest {

    @Autowired
    private AuthUserDetailsService service;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountStatusRepository accountStatusRepository;

    @Autowired
    private AuthOriginRepository authOriginRepository;

    private User activeUser;
    private User lockedUser;

    @BeforeEach
    void setUp() {
        AccountStatus active = accountStatusRepository.findBySlug(UserSlugConstants.STATUS_ACTIVE).orElseThrow();
        AccountStatus locked = accountStatusRepository.findBySlug(UserSlugConstants.STATUS_LOCKED).orElseThrow();
        AuthOrigin local = authOriginRepository.findBySlug(UserSlugConstants.ORIGIN_LOCAL).orElseThrow();

        activeUser = new User();
        activeUser.setEmail("active-" + System.nanoTime() + "@example.com");
        activeUser.setAccountStatus(active);
        activeUser.setAuthOrigin(local);
        activeUser.setCredentialsUpdatedAt(Instant.now());
        userRepository.save(activeUser);

        lockedUser = new User();
        lockedUser.setEmail("locked-" + System.nanoTime() + "@example.com");
        lockedUser.setAccountStatus(locked);
        lockedUser.setAuthOrigin(local);
        lockedUser.setCredentialsUpdatedAt(Instant.now());
        userRepository.save(lockedUser);
    }

    @Test
    void loadsExistingUserByEmail() {
        AuthenticatedUser details = (AuthenticatedUser) service.loadUserByUsername(activeUser.getEmail());
        assertThat(details.getUsername()).isEqualTo(activeUser.getEmail());
    }

    @Test
    void throwsWhenUserNotFound() {
        assertThatThrownBy(() -> service.loadUserByUsername("notfound@example.com"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void activeUserIsEnabled() {
        AuthenticatedUser details = (AuthenticatedUser) service.loadUserByUsername(activeUser.getEmail());
        assertThat(details.isEnabled()).isTrue();
    }

    @Test
    void lockedUserIsAccountNonLocked_returnsFalse() {
        AuthenticatedUser details = (AuthenticatedUser) service.loadUserByUsername(lockedUser.getEmail());
        assertThat(details.isAccountNonLocked()).isFalse();
    }

    @Test
    void lockedUserWithExpiredLockoutIsUnlocked() {
        lockedUser.setLockoutExpiresAt(Instant.now().minusSeconds(60));
        userRepository.save(lockedUser);

        AuthenticatedUser details = (AuthenticatedUser) service.loadUserByUsername(lockedUser.getEmail());
        assertThat(details.isAccountNonLocked()).isTrue();
    }

    @Test
    void toStringDoesNotExposePassword() {
        activeUser.setPasswordHash("$argon2id$v=19$m=65536,t=3,p=1$hash");
        userRepository.save(activeUser);

        AuthenticatedUser details = (AuthenticatedUser) service.loadUserByUsername(activeUser.getEmail());
        assertThat(details.toString()).doesNotContain("argon2id");
        assertThat(details.toString()).doesNotContain("passwordHash");
    }
}