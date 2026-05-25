package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.PermissionCategory;
import org.springframework.stereotype.Repository;

@Repository
public interface PermissionCategoryRepository extends LookupEntityRepository<PermissionCategory> {
}