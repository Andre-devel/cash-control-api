package com.cashcontrol.api.service;

import java.util.UUID;

public interface RbacAssignmentService {

    void assignPermissionToRole(UUID actorId, UUID roleId, UUID permissionId);

    void revokePermissionFromRole(UUID actorId, UUID roleId, UUID permissionId);

    void assignRoleToUser(UUID actorId, UUID userId, UUID roleId);

    void revokeRoleFromUser(UUID actorId, UUID userId, UUID roleId);

    void assignPermissionToUser(UUID actorId, UUID userId, UUID permissionId);

    void revokePermissionFromUser(UUID actorId, UUID userId, UUID permissionId);
}