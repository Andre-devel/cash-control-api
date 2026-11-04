package com.cashcontrol.api.repository;

/**
 * @author Andre
 * 04 de Novembro. de 2026
 */

import com.cashcontrol.api.domain.entity.SavingsGoal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SavingsGoalRepository extends JpaRepository<SavingsGoal, UUID>, UserScopedRepository {

    Optional<SavingsGoal> findByIdAndUserId(UUID id, UUID userId);

    List<SavingsGoal> findAllByUserIdAndArchivedAtIsNullOrderByCreatedAtAsc(UUID userId);

    List<SavingsGoal> findAllByUserIdOrderByCreatedAtAsc(UUID userId);
}
