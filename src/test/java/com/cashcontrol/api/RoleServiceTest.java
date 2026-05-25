package com.cashcontrol.api;

import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.domain.entity.Role;
import com.cashcontrol.api.domain.exception.ConflictException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.response.RoleResponse;
import com.cashcontrol.api.repository.RolePermissionRepository;
import com.cashcontrol.api.repository.RoleRepository;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.repository.UserRoleRepository;
import com.cashcontrol.api.service.RoleServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

    @InjectMocks private RoleServiceImpl roleService;

    @Mock private RoleRepository roleRepository;
    @Mock private RolePermissionRepository rolePermissionRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;

    @Test
    void createRole_uniqueName_savesAndReturnsResponse() {
        when(roleRepository.existsByName("MANAGER")).thenReturn(false);
        Role saved = new Role();
        saved.setName("MANAGER");
        when(roleRepository.save(any())).thenReturn(saved);
        when(rolePermissionRepository.findByRoleId(any())).thenReturn(List.of());

        RoleResponse response = roleService.createRole(null, "MANAGER", "Manages things");

        assertThat(response.name()).isEqualTo("MANAGER");
    }

    @Test
    void createRole_duplicateName_throwsConflictException() {
        when(roleRepository.existsByName("ADMIN")).thenReturn(true);

        assertThatThrownBy(() -> roleService.createRole(null, "ADMIN", null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void deleteRole_systemRole_throwsConflictException() {
        Role role = new Role();
        role.setSystemRole(true);
        when(roleRepository.findById(any())).thenReturn(Optional.of(role));

        assertThatThrownBy(() -> roleService.deleteRole(null, UUID.randomUUID()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void deleteRole_roleWithActiveUsers_throwsConflictException() {
        Role role = new Role();
        role.setSystemRole(false);
        UUID roleId = UUID.randomUUID();
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));
        when(userRoleRepository.existsByRoleId(roleId)).thenReturn(true);

        assertThatThrownBy(() -> roleService.deleteRole(null, roleId))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void deleteRole_noUsersAndNotSystem_deletesSuccessfully() {
        Role role = new Role();
        role.setName("TEMP_ROLE");
        role.setSystemRole(false);
        UUID roleId = UUID.randomUUID();
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));
        when(userRoleRepository.existsByRoleId(roleId)).thenReturn(false);

        roleService.deleteRole(null, roleId);

        verify(roleRepository).delete(role);
    }

    @Test
    void getRoleById_notFound_throwsResourceNotFoundException() {
        when(roleRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roleService.getRoleById(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}