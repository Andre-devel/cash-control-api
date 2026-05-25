package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PermissionRepository extends JpaRepository<Permission, UUID> {

    Optional<Permission> findByName(String name);

    List<Permission> findByCategoryId(UUID categoryId);

    boolean existsByName(String name);

    @Query("""
            SELECT DISTINCT p.name FROM Permission p
            JOIN RolePermission rp ON rp.permission.id = p.id
            JOIN UserRole ur ON ur.role.id = rp.role.id
            WHERE ur.user.id = :userId
            AND (ur.expiresAt IS NULL OR ur.expiresAt > CURRENT_TIMESTAMP)
            AND p.isActive = true
            """)
    List<String> findPermissionNamesByUserRoles(@Param("userId") UUID userId);

    @Query("""
            SELECT DISTINCT p.name FROM Permission p
            JOIN UserPermission up ON up.permission.id = p.id
            WHERE up.user.id = :userId
            AND (up.expiresAt IS NULL OR up.expiresAt > CURRENT_TIMESTAMP)
            AND p.isActive = true
            """)
    List<String> findDirectPermissionNamesByUser(@Param("userId") UUID userId);
}