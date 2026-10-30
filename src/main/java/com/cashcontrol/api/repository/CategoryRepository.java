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

    /** Subcategoria do sistema pelo nome dela e da categoria raiz, ex.: Investimentos › Rendimentos. */
    @Query("SELECT c FROM Category c JOIN FETCH c.parent p " +
           "WHERE c.userId IS NULL AND c.name = :name " +
           "AND p.userId IS NULL AND p.parent IS NULL AND p.name = :parentName")
    Optional<Category> findSystemSubcategory(@Param("parentName") String parentName, @Param("name") String name);

    List<Category> findAllByUserId(UUID userId);

    Optional<Category> findByIdAndUserId(UUID id, UUID userId);

    @Query("SELECT c FROM Category c WHERE c.id = :id AND (c.userId = :userId OR c.userId IS NULL)")
    Optional<Category> findByIdVisibleToUser(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("SELECT c FROM Category c WHERE c.userId = :userId AND c.parent.id = :parentId")
    List<Category> findSubcategoriesByUserIdAndParentId(@Param("userId") UUID userId, @Param("parentId") UUID parentId);

    boolean existsByUserIdAndParentIdAndName(UUID userId, UUID parentId, String name);

    /**
     * Nome já usado no mesmo nível (mesmo pai) por uma categoria ativa do usuário ou do
     * sistema, sem diferenciar maiúsculas. {@code excludeId} é a própria categoria na edição.
     */
    @Query("SELECT COUNT(c) > 0 FROM Category c " +
           "WHERE ((c.userId = :userId AND c.archivedAt IS NULL) OR c.userId IS NULL) " +
           "AND ((:parentId IS NULL AND c.parent IS NULL) OR c.parent.id = :parentId) " +
           "AND LOWER(c.name) = LOWER(:name) " +
           "AND (:excludeId IS NULL OR c.id <> :excludeId)")
    boolean existsNameInScope(@Param("userId") UUID userId, @Param("parentId") UUID parentId,
                              @Param("name") String name, @Param("excludeId") UUID excludeId);
}
