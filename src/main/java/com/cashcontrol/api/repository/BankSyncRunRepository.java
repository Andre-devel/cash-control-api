package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.BankSyncOutcome;
import com.cashcontrol.api.domain.entity.BankSyncRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BankSyncRunRepository extends JpaRepository<BankSyncRun, UUID>, JpaSpecificationExecutor<BankSyncRun> {

    List<BankSyncRun> findAllByUserIdAndStartedAtGreaterThanEqual(UUID userId, Instant from);

    Optional<BankSyncRun> findFirstByUserIdAndConnectionIdOrderByStartedAtDesc(UUID userId, UUID connectionId);

    Optional<BankSyncRun> findFirstByUserIdAndConnectionIdAndOutcomeInOrderByStartedAtDesc(
            UUID userId, UUID connectionId, Collection<BankSyncOutcome> outcomes);

    @Modifying
    @Query("DELETE FROM BankSyncRun r WHERE r.startedAt < :cutoff")
    int deleteStartedBefore(@Param("cutoff") Instant cutoff);
}
