package com.cashcontrol.api.service;

import com.cashcontrol.api.dto.response.PermissionResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface PermissionService {

    PermissionResponse createPermission(UUID actorId, String name, String description, UUID categoryId);

    void deletePermission(UUID actorId, UUID permissionId);

    Page<PermissionResponse> listPermissions(Pageable pageable);
}