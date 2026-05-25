package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountStatus;
import com.cashcontrol.api.domain.entity.AuthOrigin;
import com.cashcontrol.api.domain.entity.Role;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.entity.UserRole;
import com.cashcontrol.api.repository.AccountStatusRepository;
import com.cashcontrol.api.repository.AuthOriginRepository;
import com.cashcontrol.api.repository.RoleRepository;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.repository.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class UserRoleRepositoryTest {

    @Autowired private UserRoleRepository userRoleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private AccountStatusRepository accountStatusRepository;
    @Autowired private AuthOriginRepository authOriginRepository;

    private User testUser;
    private Role userRole;

    @BeforeEach
    @Transactional
    void setUp() {
        AccountStatus active = accountStatusRepository.findBySlug("ACTIVE").orElseThrow();
        AuthOrigin local = authOriginRepository.findBySlug("LOCAL").orElseThrow();

        User user = new User();
        user.setEmail("userrole-test-" + System.nanoTime() + "@example.com");
        user.setAccountStatus(active);
        user.setAuthOrigin(local);
        user.setCredentialsUpdatedAt(Instant.now());
        testUser = userRepository.save(user);

        userRole = roleRepository.findByNameIgnoreCase("USER").orElseThrow();
    }

    @Test
    @Transactional
    void findByUserIdReturnsAssignedRoles() {
        UserRole ur = new UserRole();
        ur.setUser(testUser);
        ur.setRole(userRole);
        ur.setGrantedAt(Instant.now());
        userRoleRepository.save(ur);

        List<UserRole> roles = userRoleRepository.findByUserId(testUser.getId());
        assertThat(roles).hasSize(1);
        assertThat(roles.get(0).getRole().getName()).isEqualTo("USER");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void duplicateUserRoleThrowsDataIntegrityViolationException() {
        // First insert
        UserRole first = new UserRole();
        first.setUser(testUser);
        first.setRole(userRole);
        first.setGrantedAt(Instant.now());
        userRoleRepository.saveAndFlush(first);

        // Duplicate insert should throw
        UserRole duplicate = new UserRole();
        duplicate.setUser(testUser);
        duplicate.setRole(userRole);
        duplicate.setGrantedAt(Instant.now());

        assertThatThrownBy(() -> userRoleRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);

        // cleanup
        userRoleRepository.deleteByUserIdAndRoleId(testUser.getId(), userRole.getId());
    }

    @Test
    @Transactional
    void existsByUserIdAndRoleIdReturnsTrueWhenAssigned() {
        UserRole ur = new UserRole();
        ur.setUser(testUser);
        ur.setRole(userRole);
        ur.setGrantedAt(Instant.now());
        userRoleRepository.save(ur);

        assertThat(userRoleRepository.existsByUserIdAndRoleId(testUser.getId(), userRole.getId())).isTrue();
    }
}