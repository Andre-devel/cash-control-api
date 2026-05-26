package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.RecurrenceRule;
import com.cashcontrol.api.domain.entity.RecurrenceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RecurrenceRepository extends JpaRepository<RecurrenceRule, UUID> {

    Optional<RecurrenceRule> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    List<RecurrenceRule> findAllByUserIdAndDeletedAtIsNull(UUID userId);

    @Query("SELECT r FROM RecurrenceRule r " +
           "WHERE r.status = 'ACTIVE' " +
           "AND r.deletedAt IS NULL " +
           "AND (r.nextOccurrenceDate IS NULL OR r.nextOccurrenceDate <= :cutoff)")
    List<RecurrenceRule> findActiveRulesDueBy(@Param("cutoff") LocalDate cutoff);
}
