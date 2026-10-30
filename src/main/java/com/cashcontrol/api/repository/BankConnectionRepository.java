package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.BankConnection;
import com.cashcontrol.api.domain.entity.BankProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BankConnectionRepository extends JpaRepository<BankConnection, UUID> {

    List<BankConnection> findAllByUserIdOrderByCreatedAtAsc(UUID userId);

    Optional<BankConnection> findByIdAndUserId(UUID id, UUID userId);

    Optional<BankConnection> findByProviderAndItemId(BankProvider provider, String itemId);
}
