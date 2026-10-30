package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.BankPendingTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BankPendingTransactionRepository extends JpaRepository<BankPendingTransaction, UUID> {

    List<BankPendingTransaction> findAllByUserIdAndDiscardedAtIsNullOrderByDateAscCreatedAtAsc(UUID userId);

    long countByUserIdAndDiscardedAtIsNull(UUID userId);

    Optional<BankPendingTransaction> findByIdAndUserIdAndDiscardedAtIsNull(UUID id, UUID userId);

    @Query("SELECT p.externalRef FROM BankPendingTransaction p "
           + "WHERE p.accountId = :accountId AND p.externalRef IN :externalRefs")
    List<String> findExistingExternalRefs(@Param("accountId") UUID accountId,
                                          @Param("externalRefs") Collection<String> externalRefs);

    /**
     * Tira da fila o que já virou lançamento, por qualquer caminho: confirmado aqui ou importado
     * pelo diálogo "Buscar do banco". A descartada fica (é o que impede o webhook de trazê-la de novo).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            DELETE FROM bank_pending_transactions p
            WHERE p.user_id = :userId
              AND EXISTS (SELECT 1 FROM transactions t
                          WHERE t.user_id = p.user_id AND t.account_id = p.account_id
                            AND t.external_ref = p.external_ref)
            """, nativeQuery = true)
    int deleteImported(@Param("userId") UUID userId);
}
