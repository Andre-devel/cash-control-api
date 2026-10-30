package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.FixedIncomeHolding;
import com.cashcontrol.api.domain.entity.FixedIncomeStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FixedIncomeHoldingRepository extends JpaRepository<FixedIncomeHolding, UUID> {

    @EntityGraph(attributePaths = "account")
    Optional<FixedIncomeHolding> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    @EntityGraph(attributePaths = "account")
    List<FixedIncomeHolding> findAllByUserIdAndDeletedAtIsNullOrderByAppliedAtAsc(UUID userId);

    @EntityGraph(attributePaths = "account")
    List<FixedIncomeHolding> findAllByUserIdAndStatusAndDeletedAtIsNullOrderByAppliedAtAsc(UUID userId,
                                                                                            FixedIncomeStatus status);
}
