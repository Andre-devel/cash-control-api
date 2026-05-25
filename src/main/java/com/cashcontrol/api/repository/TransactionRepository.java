package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByIdAndUserId(UUID id, UUID userId);

    Page<Transaction> findAllByUserId(UUID userId, Pageable pageable);

    Page<Transaction> findAllByAccount_IdAndUserId(UUID accountId, UUID userId, Pageable pageable);

    boolean existsByAccount_IdAndUserIdAndStatusNotIn(UUID accountId, UUID userId, List<TransactionStatus> statuses);

    @Query("SELECT COALESCE(SUM(CASE " +
           "WHEN t.type = com.cashcontrol.api.domain.entity.TransactionType.INCOME " +
           "  OR t.type = com.cashcontrol.api.domain.entity.TransactionType.REFUND THEN t.amount " +
           "WHEN t.type = com.cashcontrol.api.domain.entity.TransactionType.EXPENSE " +
           "  OR t.type = com.cashcontrol.api.domain.entity.TransactionType.TRANSFER THEN -t.amount " +
           "ELSE t.amount END), 0) " +
           "FROM Transaction t " +
           "WHERE t.account.id = :accountId AND t.userId = :userId " +
           "AND t.status = com.cashcontrol.api.domain.entity.TransactionStatus.PAID")
    BigDecimal sumPaidAmountByAccountIdAndUserId(
            @Param("accountId") UUID accountId,
            @Param("userId") UUID userId);

    List<Transaction> findAllByTransferGroupId(UUID transferGroupId);

    List<Transaction> findAllByInstallmentSeries_Id(UUID installmentSeriesId);
}
