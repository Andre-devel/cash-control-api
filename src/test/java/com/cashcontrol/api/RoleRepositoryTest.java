package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.Role;
import com.cashcontrol.api.repository.RoleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class RoleRepositoryTest {

    @Autowired
    private RoleRepository roleRepository;

    @Test
    void findByNameIgnoreCaseReturnsSeededAdminRole() {
        Optional<Role> admin = roleRepository.findByNameIgnoreCase("ADMIN");
        assertThat(admin).isPresent();
        assertThat(admin.get().isSystemRole()).isTrue();
    }

    @Test
    void findByNameIgnoreCaseIsCaseInsensitive() {
        Optional<Role> admin = roleRepository.findByNameIgnoreCase("admin");
        assertThat(admin).isPresent();
        assertThat(admin.get().getName()).isEqualTo("ADMIN");
    }

    @Test
    void findByNameIgnoreCaseReturnsEmptyForUnknownRole() {
        Optional<Role> role = roleRepository.findByNameIgnoreCase("UNKNOWN_ROLE_XYZ");
        assertThat(role).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void duplicateRoleNameThrowsDataIntegrityViolationException() {
        Role duplicate = new Role();
        duplicate.setName("ADMIN"); // already seeded

        assertThatThrownBy(() -> roleRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void seededRolesHaveCorrectProperties() {
        Optional<Role> user = roleRepository.findByNameIgnoreCase("USER");
        assertThat(user).isPresent();
        assertThat(user.get().isSystemRole()).isTrue();
        assertThat(user.get().isActive()).isTrue();
    }
}