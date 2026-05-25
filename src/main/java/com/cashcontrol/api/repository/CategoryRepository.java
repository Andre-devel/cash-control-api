package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID> {

    @Query("SELECT c FROM Category c WHERE c.userId IS NULL")
    List<Category> findAllSystemCategories();

    List<Category> findAllByUserId(UUID userId);

    boolean existsByUserIdAndParentIdAndName(UUID userId, UUID parentId, String name);
}
