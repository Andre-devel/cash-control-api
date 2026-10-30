package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.BankConnectionAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BankConnectionAccountRepository extends JpaRepository<BankConnectionAccount, UUID> {

    List<BankConnectionAccount> findAllByConnectionIdIn(Collection<UUID> connectionIds);

    List<BankConnectionAccount> findAllByConnectionId(UUID connectionId);

    Optional<BankConnectionAccount> findByIdAndConnectionId(UUID id, UUID connectionId);

    Optional<BankConnectionAccount> findByAccountId(UUID accountId);

    boolean existsByAccountIdAndIdNot(UUID accountId, UUID id);

    boolean existsByCreditCardIdAndIdNot(UUID creditCardId, UUID id);
}
