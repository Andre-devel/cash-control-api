package com.cashcontrol.api.service;

import com.cashcontrol.api.audit.AuditEventSlug;
import com.cashcontrol.api.audit.AuditOutcomeSlug;
import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.domain.entity.RolePermission;
import com.cashcontrol.api.domain.entity.UserPermission;
import com.cashcontrol.api.domain.entity.UserRole;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.repository.PermissionRepository;
import com.cashcontrol.api.repository.RolePermissionRepository;
import com.cashcontrol.api.repository.RoleRepository;
import com.cashcontrol.api.repository.UserPermissionRepository;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RbacAssignmentServiceImpl implements RbacAssignmentService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserPermissionRepository userPermissionRepository;
    private final AuditService auditService;

    @Override
    @Transactional
    public void assignPermissionToRole(UUID actorId, UUID roleId, UUID permissionId) {
        requireRole(roleId);
        requirePermission(permissionId);

        if (rolePermissionRepository.existsByRoleIdAndPermissionId(roleId, permissionId)) {
            return; // idempotent
        }

        RolePermission rp = new RolePermission();
        rp.setRole(roleRepository.getReferenceById(roleId));
        rp.setPermission(permissionRepository.getReferenceById(permissionId));
        if (actorId != null) rp.setGrantedBy(userRepository.getReferenceById(actorId));
        rolePermissionRepository.save(rp);

        auditService.record(AuditEventSlug.PERMISSION_GRANTED, AuditOutcomeSlug.SUCCESS, actorId, null,
                Map.of("roleId", roleId, "permissionId", permissionId));
    }

    @Override
    @Transactional
    public void revokePermissionFromRole(UUID actorId, UUID roleId, UUID permissionId) {
        requireRole(roleId);
        requirePermission(permissionId);

        if (!rolePermissionRepository.existsByRoleIdAndPermissionId(roleId, permissionId)) {
            return; // idempotent
        }

        rolePermissionRepository.deleteByRoleIdAndPermissionId(roleId, permissionId);

        auditService.record(AuditEventSlug.PERMISSION_REVOKED, AuditOutcomeSlug.SUCCESS, actorId, null,
                Map.of("roleId", roleId, "permissionId", permissionId));
    }

    @Override
    @Transactional
    public void assignRoleToUser(UUID actorId, UUID userId, UUID roleId) {
        requireUser(userId);
        requireRole(roleId);

        if (userRoleRepository.existsByUserIdAndRoleId(userId, roleId)) {
            return; // idempotent
        }

        UserRole ur = new UserRole();
        ur.setUser(userRepository.getReferenceById(userId));
        ur.setRole(roleRepository.getReferenceById(roleId));
        if (actorId != null) ur.setGrantedBy(userRepository.getReferenceById(actorId));
        userRoleRepository.save(ur);

        auditService.record(AuditEventSlug.ROLE_ASSIGNED, AuditOutcomeSlug.SUCCESS, actorId, userId,
                Map.of("roleId", roleId));
    }

    @Override
    @Transactional
    public void revokeRoleFromUser(UUID actorId, UUID userId, UUID roleId) {
        requireUser(userId);
        requireRole(roleId);

        if (!userRoleRepository.existsByUserIdAndRoleId(userId, roleId)) {
            return; // idempotent
        }

        userRoleRepository.deleteByUserIdAndRoleId(userId, roleId);

        auditService.record(AuditEventSlug.ROLE_REMOVED, AuditOutcomeSlug.SUCCESS, actorId, userId,
                Map.of("roleId", roleId));
    }

    @Override
    @Transactional
    public void assignPermissionToUser(UUID actorId, UUID userId, UUID permissionId) {
        requireUser(userId);
        requirePermission(permissionId);

        if (userPermissionRepository.existsByUserIdAndPermissionId(userId, permissionId)) {
            return; // idempotent
        }

        UserPermission up = new UserPermission();
        up.setUser(userRepository.getReferenceById(userId));
        up.setPermission(permissionRepository.getReferenceById(permissionId));
        if (actorId != null) up.setGrantedBy(userRepository.getReferenceById(actorId));
        userPermissionRepository.save(up);

        auditService.record(AuditEventSlug.PERMISSION_GRANTED, AuditOutcomeSlug.SUCCESS, actorId, userId,
                Map.of("permissionId", permissionId, "direct", true));
    }

    @Override
    @Transactional
    public void revokePermissionFromUser(UUID actorId, UUID userId, UUID permissionId) {
        requireUser(userId);
        requirePermission(permissionId);

        if (!userPermissionRepository.existsByUserIdAndPermissionId(userId, permissionId)) {
            return; // idempotent
        }

        userPermissionRepository.deleteByUserIdAndPermissionId(userId, permissionId);

        auditService.record(AuditEventSlug.PERMISSION_REVOKED, AuditOutcomeSlug.SUCCESS, actorId, userId,
                Map.of("permissionId", permissionId, "direct", true));
    }

    private void requireUser(UUID userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User not found.");
        }
    }

    private void requireRole(UUID roleId) {
        if (!roleRepository.existsById(roleId)) {
            throw new ResourceNotFoundException("Role not found.");
        }
    }

    private void requirePermission(UUID permissionId) {
        if (!permissionRepository.existsById(permissionId)) {
            throw new ResourceNotFoundException("Permission not found.");
        }
    }
}