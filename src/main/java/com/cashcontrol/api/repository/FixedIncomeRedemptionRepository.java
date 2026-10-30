package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.FixedIncomeRedemption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface FixedIncomeRedemptionRepository extends JpaRepository<FixedIncomeRedemption, UUID> {

    List<FixedIncomeRedemption> findAllByHoldingIdOrderByRedeemedAtAsc(UUID holdingId);

    List<FixedIncomeRedemption> findAllByHoldingIdInOrderByRedeemedAtAsc(Collection<UUID> holdingIds);

    boolean existsByHoldingId(UUID holdingId);

    boolean existsByTransferGroupId(UUID transferGroupId);
}
