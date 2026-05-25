package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.RecurrenceRule;
import com.cashcontrol.api.domain.entity.RecurrenceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RecurrenceRuleRepository extends JpaRepository<RecurrenceRule, UUID> {

    Optional<RecurrenceRule> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    List<RecurrenceRule> findAllByUserIdAndDeletedAtIsNull(UUID userId);

    List<RecurrenceRule> findAllByStatusAndNextOccurrenceDateLessThanEqual(
            RecurrenceStatus status, LocalDate date);
}
