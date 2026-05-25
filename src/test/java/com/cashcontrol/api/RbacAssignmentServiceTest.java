package com.cashcontrol.api;

import com.cashcontrol.api.audit.AuditEventSlug;
import com.cashcontrol.api.audit.AuditOutcomeSlug;
import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.repository.PermissionRepository;
import com.cashcontrol.api.repository.RolePermissionRepository;
import com.cashcontrol.api.repository.RoleRepository;
import com.cashcontrol.api.repository.UserPermissionRepository;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.repository.UserRoleRepository;
import com.cashcontrol.api.service.RbacAssignmentServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RbacAssignmentServiceTest {

    @InjectMocks private RbacAssignmentServiceImpl service;

    @Mock private RoleRepository roleRepository;
    @Mock private PermissionRepository permissionRepository;
    @Mock private UserRepository userRepository;
    @Mock private RolePermissionRepository rolePermissionRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private UserPermissionRepository userPermissionRepository;
    @Mock private AuditService auditService;

    @Test
    void assignPermissionToRole_idempotent_doesNotDuplicate() {
        UUID roleId = UUID.randomUUID();
        UUID permId = UUID.randomUUID();
        when(roleRepository.existsById(roleId)).thenReturn(true);
        when(permissionRepository.existsById(permId)).thenReturn(true);
        when(rolePermissionRepository.existsByRoleIdAndPermissionId(roleId, permId)).thenReturn(true);

        service.assignPermissionToRole(null, roleId, permId);

        verify(rolePermissionRepository, never()).save(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void assignPermissionToRole_newAssignment_savesAndAudits() {
        UUID roleId = UUID.randomUUID();
        UUID permId = UUID.randomUUID();
        when(roleRepository.existsById(roleId)).thenReturn(true);
        when(permissionRepository.existsById(permId)).thenReturn(true);
        when(rolePermissionRepository.existsByRoleIdAndPermissionId(roleId, permId)).thenReturn(false);
        when(roleRepository.getReferenceById(roleId)).thenReturn(new com.cashcontrol.api.domain.entity.Role());
        when(permissionRepository.getReferenceById(permId)).thenReturn(new com.cashcontrol.api.domain.entity.Permission());

        service.assignPermissionToRole(null, roleId, permId);

        verify(rolePermissionRepository).save(any());
        verify(auditService).record(eq(AuditEventSlug.PERMISSION_GRANTED), eq(AuditOutcomeSlug.SUCCESS),
                any(), any(), any());
    }

    @Test
    void revokePermissionFromRole_existingAssignment_deletesAndAudits() {
        UUID roleId = UUID.randomUUID();
        UUID permId = UUID.randomUUID();
        when(roleRepository.existsById(roleId)).thenReturn(true);
        when(permissionRepository.existsById(permId)).thenReturn(true);
        when(rolePermissionRepository.existsByRoleIdAndPermissionId(roleId, permId)).thenReturn(true);

        service.revokePermissionFromRole(null, roleId, permId);

        verify(rolePermissionRepository).deleteByRoleIdAndPermissionId(roleId, permId);
        verify(auditService).record(eq(AuditEventSlug.PERMISSION_REVOKED), eq(AuditOutcomeSlug.SUCCESS),
                any(), any(), any());
    }

    @Test
    void revokePermissionFromRole_noAssignment_isNoOp() {
        UUID roleId = UUID.randomUUID();
        UUID permId = UUID.randomUUID();
        when(roleRepository.existsById(roleId)).thenReturn(true);
        when(permissionRepository.existsById(permId)).thenReturn(true);
        when(rolePermissionRepository.existsByRoleIdAndPermissionId(roleId, permId)).thenReturn(false);

        service.revokePermissionFromRole(null, roleId, permId);

        verify(rolePermissionRepository, never()).deleteByRoleIdAndPermissionId(any(), any());
    }

    @Test
    void assignRoleToUser_newAssignment_savesAndAudits() {
        UUID actorId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        when(userRepository.existsById(userId)).thenReturn(true);
        when(roleRepository.existsById(roleId)).thenReturn(true);
        when(userRoleRepository.existsByUserIdAndRoleId(userId, roleId)).thenReturn(false);
        when(userRepository.getReferenceById(userId)).thenReturn(new com.cashcontrol.api.domain.entity.User());
        when(roleRepository.getReferenceById(roleId)).thenReturn(new com.cashcontrol.api.domain.entity.Role());
        when(userRepository.getReferenceById(actorId)).thenReturn(new com.cashcontrol.api.domain.entity.User());

        service.assignRoleToUser(actorId, userId, roleId);

        verify(userRoleRepository).save(any());
        verify(auditService).record(eq(AuditEventSlug.ROLE_ASSIGNED), eq(AuditOutcomeSlug.SUCCESS),
                eq(actorId), eq(userId), any());
    }

    @Test
    void revokeRoleFromUser_existingAssignment_deletesAndAudits() {
        UUID actorId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        when(userRepository.existsById(userId)).thenReturn(true);
        when(roleRepository.existsById(roleId)).thenReturn(true);
        when(userRoleRepository.existsByUserIdAndRoleId(userId, roleId)).thenReturn(true);

        service.revokeRoleFromUser(actorId, userId, roleId);

        verify(userRoleRepository).deleteByUserIdAndRoleId(userId, roleId);
        verify(auditService).record(eq(AuditEventSlug.ROLE_REMOVED), eq(AuditOutcomeSlug.SUCCESS),
                eq(actorId), eq(userId), any());
    }
}