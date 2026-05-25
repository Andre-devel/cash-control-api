package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.CategoryRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CategoryRuleRepository extends JpaRepository<CategoryRule, UUID> {

    List<CategoryRule> findAllByUserIdAndIsActiveTrueOrderByPriorityAsc(UUID userId);

    Optional<CategoryRule> findByIdAndUserId(UUID id, UUID userId);
}
