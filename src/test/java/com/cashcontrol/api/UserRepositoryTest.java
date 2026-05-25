package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountStatus;
import com.cashcontrol.api.domain.entity.AuthOrigin;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.repository.AccountStatusRepository;
import com.cashcontrol.api.repository.AuthOriginRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AccountStatusRepository accountStatusRepository;
    @Autowired
    private AuthOriginRepository authOriginRepository;

    private AccountStatus activeStatus;
    private AuthOrigin localOrigin;

    @BeforeEach
    void setUp() {
        activeStatus = accountStatusRepository.findBySlug("ACTIVE")
                .orElseThrow(() -> new IllegalStateException("ACTIVE status not seeded"));
        localOrigin = authOriginRepository.findBySlug("LOCAL")
                .orElseThrow(() -> new IllegalStateException("LOCAL origin not seeded"));
    }

    private User buildUser(String email) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("$argon2id$v=19$test");
        user.setAccountStatus(activeStatus);
        user.setAuthOrigin(localOrigin);
        user.setCredentialsUpdatedAt(Instant.now());
        return user;
    }

    @Test
    void findByEmailAndDeletedAtIsNullReturnsActiveUser() {
        User saved = userRepository.save(buildUser("active@example.com"));

        Optional<User> found = userRepository.findByEmailAndDeletedAtIsNull("active@example.com");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    void findByEmailAndDeletedAtIsNullExcludesSoftDeletedUser() {
        User user = buildUser("deleted@example.com");
        user.setDeletedAt(Instant.now());
        userRepository.save(user);

        Optional<User> found = userRepository.findByEmailAndDeletedAtIsNull("deleted@example.com");

        assertThat(found).isEmpty();
    }

    @Test
    void findByIdAndDeletedAtIsNullReturnsUser() {
        User saved = userRepository.save(buildUser("byid@example.com"));

        Optional<User> found = userRepository.findByIdAndDeletedAtIsNull(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getEmail()).isEqualTo("byid@example.com");
    }

    @Test
    void findByIdAndDeletedAtIsNullExcludesSoftDeleted() {
        User user = buildUser("softdeleted@example.com");
        user.setDeletedAt(Instant.now());
        User saved = userRepository.save(user);

        Optional<User> found = userRepository.findByIdAndDeletedAtIsNull(saved.getId());

        assertThat(found).isEmpty();
    }

    @Test
    void existsByEmailAndDeletedAtIsNullReturnsTrueForActiveUser() {
        userRepository.save(buildUser("exists@example.com"));

        assertThat(userRepository.existsByEmailAndDeletedAtIsNull("exists@example.com")).isTrue();
        assertThat(userRepository.existsByEmailAndDeletedAtIsNull("notexists@example.com")).isFalse();
    }

    @Test
    void findByIdReturnsEmptyForUnknownId() {
        Optional<User> found = userRepository.findById(UUID.randomUUID());
        assertThat(found).isEmpty();
    }
}