package com.cashcontrol.api.service;

import com.cashcontrol.api.dto.response.PermissionResponse;
import com.cashcontrol.api.dto.response.RoleResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface RoleService {

    RoleResponse createRole(UUID actorId, String name, String description);

    RoleResponse updateRole(UUID actorId, UUID roleId, String description);

    void deleteRole(UUID actorId, UUID roleId);

    Page<RoleResponse> listRoles(Pageable pageable);

    RoleResponse getRoleById(UUID roleId);

    List<PermissionResponse> listRolePermissions(UUID roleId);
}