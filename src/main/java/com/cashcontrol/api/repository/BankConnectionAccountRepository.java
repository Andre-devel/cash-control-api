package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.BankConnectionAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    Optional<BankConnectionAccount> findByCreditCardId(UUID creditCardId);

    /** As contas do usuário ligadas ao banco e com o documento do titular conhecido. */
    @Query("SELECT bca FROM BankConnectionAccount bca, BankConnection c WHERE c.id = bca.connectionId "
           + "AND c.userId = :userId AND bca.accountId IS NOT NULL AND bca.taxNumber IS NOT NULL")
    List<BankConnectionAccount> findLinkedWithTaxNumber(@Param("userId") UUID userId);

    boolean existsByAccountIdAndIdNot(UUID accountId, UUID id);

    boolean existsByCreditCardIdAndIdNot(UUID creditCardId, UUID id);
}
