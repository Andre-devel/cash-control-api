package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, UUID> {

    List<PushSubscription> findAllByUserId(UUID userId);

    @Query("SELECT DISTINCT s.userId FROM PushSubscription s WHERE s.budgetAlerts = true")
    List<UUID> findUserIdsWithBudgetAlerts();

    Optional<PushSubscription> findByEndpoint(String endpoint);

    Optional<PushSubscription> findByEndpointAndUserId(String endpoint, UUID userId);
}
