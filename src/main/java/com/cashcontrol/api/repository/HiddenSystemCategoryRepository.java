package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.HiddenSystemCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Set;
import java.util.UUID;

@Repository
public interface HiddenSystemCategoryRepository extends JpaRepository<HiddenSystemCategory, UUID> {

    @Query("SELECT h.categoryId FROM HiddenSystemCategory h WHERE h.userId = :userId")
    Set<UUID> findCategoryIdsByUserId(@Param("userId") UUID userId);

    boolean existsByUserIdAndCategoryId(UUID userId, UUID categoryId);

    void deleteByUserIdAndCategoryId(UUID userId, UUID categoryId);
}
