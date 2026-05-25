package com.cashcontrol.api.security;

import com.cashcontrol.api.repository.PermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PermissionResolver {

    private final PermissionRepository permissionRepository;

    @Transactional(readOnly = true)
    public List<String> resolveEffectivePermissions(UUID userId) {
        Set<String> permissions = new LinkedHashSet<>();
        permissions.addAll(permissionRepository.findPermissionNamesByUserRoles(userId));
        permissions.addAll(permissionRepository.findDirectPermissionNamesByUser(userId));
        return List.copyOf(permissions);
    }
}