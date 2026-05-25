package com.cashcontrol.api.service;

import com.cashcontrol.api.audit.AuditEventSlug;
import com.cashcontrol.api.audit.AuditOutcomeSlug;
import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.domain.entity.Permission;
import com.cashcontrol.api.domain.entity.Role;
import com.cashcontrol.api.domain.exception.ConflictException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.response.PermissionResponse;
import com.cashcontrol.api.dto.response.RoleResponse;
import com.cashcontrol.api.repository.RolePermissionRepository;
import com.cashcontrol.api.repository.RoleRepository;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    @Override
    @Transactional
    public RoleResponse createRole(UUID actorId, String name, String description) {
        if (roleRepository.existsByName(name)) {
            throw new ConflictException("Role name already exists: " + name);
        }

        Role role = new Role();
        role.setName(name);
        role.setDescription(description);
        if (actorId != null) {
            role.setCreatedBy(userRepository.getReferenceById(actorId));
        }
        Role saved = roleRepository.save(role);

        auditService.record(AuditEventSlug.ROLE_CREATED, AuditOutcomeSlug.SUCCESS, actorId, null,
                Map.of("roleName", name));

        return toResponse(saved);
    }

    @Override
    @Transactional
    public RoleResponse updateRole(UUID actorId, UUID roleId, String description) {
        Role role = requireRole(roleId);
        role.setDescription(description);
        return toResponse(roleRepository.save(role));
    }

    @Override
    @Transactional
    public void deleteRole(UUID actorId, UUID roleId) {
        Role role = requireRole(roleId);

        if (role.isSystemRole()) {
            throw new ConflictException("System roles cannot be deleted.");
        }
        if (userRoleRepository.existsByRoleId(roleId)) {
            throw new ConflictException("Role is assigned to one or more users and cannot be deleted.");
        }

        roleRepository.delete(role);
        auditService.record(AuditEventSlug.ROLE_REMOVED, AuditOutcomeSlug.SUCCESS, actorId, null,
                Map.of("roleName", role.getName()));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RoleResponse> listRoles(Pageable pageable) {
        return roleRepository.findAll(pageable).map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public RoleResponse getRoleById(UUID roleId) {
        return toResponse(requireRole(roleId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PermissionResponse> listRolePermissions(UUID roleId) {
        requireRole(roleId);
        return rolePermissionRepository.findByRoleId(roleId).stream()
                .map(rp -> toPermissionResponse(rp.getPermission()))
                .toList();
    }

    private Role requireRole(UUID roleId) {
        return roleRepository.findById(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found."));
    }

    private RoleResponse toResponse(Role role) {
        int permCount = rolePermissionRepository.findByRoleId(role.getId()).size();
        return new RoleResponse(
                role.getId(),
                role.getName(),
                role.getDescription(),
                role.isSystemRole(),
                permCount,
                role.getCreatedAt()
        );
    }

    private PermissionResponse toPermissionResponse(Permission perm) {
        String category = perm.getCategory() != null ? perm.getCategory().getSlug() : null;
        return new PermissionResponse(
                perm.getId(),
                perm.getName(),
                perm.getDescription(),
                category,
                perm.isSystemPerm()
        );
    }
}