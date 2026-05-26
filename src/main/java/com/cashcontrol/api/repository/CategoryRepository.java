package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID> {

    @Query("SELECT c FROM Category c WHERE c.userId IS NULL")
    List<Category> findAllSystemCategories();

    List<Category> findAllByUserId(UUID userId);

    Optional<Category> findByIdAndUserId(UUID id, UUID userId);

    @Query("SELECT c FROM Category c WHERE c.id = :id AND (c.userId = :userId OR c.userId IS NULL)")
    Optional<Category> findByIdVisibleToUser(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("SELECT c FROM Category c WHERE c.userId = :userId AND c.parent.id = :parentId")
    List<Category> findSubcategoriesByUserIdAndParentId(@Param("userId") UUID userId, @Param("parentId") UUID parentId);

    boolean existsByUserIdAndParentIdAndName(UUID userId, UUID parentId, String name);

    boolean existsByUserIdAndParentIdAndNameAndArchivedAtIsNull(UUID userId, UUID parentId, String name);

    boolean existsByUserIdAndParentIdAndNameAndArchivedAtIsNullAndIdNot(UUID userId, UUID parentId, String name, UUID excludeId);
}
