package com.cashcontrol.api.service;

import com.cashcontrol.api.audit.AuditEventSlug;
import com.cashcontrol.api.audit.AuditOutcomeSlug;
import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.domain.entity.Permission;
import com.cashcontrol.api.domain.exception.ConflictException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.response.PermissionResponse;
import com.cashcontrol.api.repository.LookupCache;
import com.cashcontrol.api.repository.PermissionCategoryRepository;
import com.cashcontrol.api.repository.PermissionRepository;
import com.cashcontrol.api.repository.RolePermissionRepository;
import com.cashcontrol.api.repository.UserPermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionService {

    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserPermissionRepository userPermissionRepository;
    private final PermissionCategoryRepository permissionCategoryRepository;
    private final LookupCache lookupCache;
    private final AuditService auditService;

    @Override
    @Transactional
    public PermissionResponse createPermission(UUID actorId, String name, String description, UUID categoryId) {
        if (permissionRepository.existsByName(name)) {
            throw new ConflictException("Permission name already exists: " + name);
        }

        Permission perm = new Permission();
        perm.setName(name);
        perm.setDescription(description);
        if (categoryId != null) {
            perm.setCategory(permissionCategoryRepository.getReferenceById(categoryId));
        }
        Permission saved = permissionRepository.save(perm);

        auditService.record(AuditEventSlug.PERMISSION_GRANTED, AuditOutcomeSlug.SUCCESS, actorId, null,
                Map.of("permissionName", name, "action", "CREATED"));

        return toResponse(saved);
    }

    @Override
    @Transactional
    public void deletePermission(UUID actorId, UUID permissionId) {
        Permission perm = permissionRepository.findById(permissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Permission not found."));

        if (perm.isSystemPerm()) {
            throw new ConflictException("System permissions cannot be deleted.");
        }
        if (rolePermissionRepository.existsByPermissionId(permissionId)) {
            throw new ConflictException("Permission is assigned to one or more roles and cannot be deleted.");
        }
        if (userPermissionRepository.existsByPermissionId(permissionId)) {
            throw new ConflictException("Permission is directly assigned to one or more users and cannot be deleted.");
        }

        permissionRepository.delete(perm);
        auditService.record(AuditEventSlug.PERMISSION_REVOKED, AuditOutcomeSlug.SUCCESS, actorId, null,
                Map.of("permissionName", perm.getName(), "action", "DELETED"));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PermissionResponse> listPermissions(Pageable pageable) {
        return permissionRepository.findAll(pageable).map(this::toResponse);
    }

    private PermissionResponse toResponse(Permission perm) {
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