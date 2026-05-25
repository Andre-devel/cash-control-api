package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.UserPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public interface UserPermissionRepository extends JpaRepository<UserPermission, UUID> {

    List<UserPermission> findByUserId(UUID userId);

    boolean existsByUserIdAndPermissionId(UUID userId, UUID permissionId);

    boolean existsByPermissionId(UUID permissionId);

    @Modifying
    @Transactional
    @Query("DELETE FROM UserPermission up WHERE up.user.id = :userId AND up.permission.id = :permissionId")
    void deleteByUserIdAndPermissionId(@Param("userId") UUID userId, @Param("permissionId") UUID permissionId);
}