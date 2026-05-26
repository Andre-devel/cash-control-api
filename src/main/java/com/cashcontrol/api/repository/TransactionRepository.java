package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByIdAndUserId(UUID id, UUID userId);

    Page<Transaction> findAllByUserId(UUID userId, Pageable pageable);

    Page<Transaction> findAllByAccount_IdAndUserId(UUID accountId, UUID userId, Pageable pageable);

    boolean existsByAccount_IdAndUserIdAndStatusNotIn(UUID accountId, UUID userId, List<TransactionStatus> statuses);

    // TRANSFER amounts are stored signed: source leg = negative, destination leg = positive.
    // MANUAL_ADJUSTMENT amounts are also signed (positive = increase, negative = decrease).
    @Query("SELECT COALESCE(SUM(CASE " +
           "WHEN t.type = com.cashcontrol.api.domain.entity.TransactionType.INCOME " +
           "  OR t.type = com.cashcontrol.api.domain.entity.TransactionType.REFUND THEN t.amount " +
           "WHEN t.type = com.cashcontrol.api.domain.entity.TransactionType.EXPENSE THEN -t.amount " +
           "ELSE t.amount END), 0) " +
           "FROM Transaction t " +
           "WHERE t.account.id = :accountId AND t.userId = :userId " +
           "AND t.status = com.cashcontrol.api.domain.entity.TransactionStatus.PAID")
    BigDecimal sumPaidAmountByAccountIdAndUserId(
            @Param("accountId") UUID accountId,
            @Param("userId") UUID userId);

    long countByAccount_IdAndUserIdAndStatusNot(UUID accountId, UUID userId, TransactionStatus status);

    List<Transaction> findAllByTransferGroupId(UUID transferGroupId);

    List<Transaction> findAllByInstallmentSeries_Id(UUID installmentSeriesId);

    @Query(value = "SELECT t FROM Transaction t " +
                   "WHERE t.userId = :userId " +
                   "AND (:accountId IS NULL OR t.account.id = :accountId) " +
                   "AND (:type IS NULL OR t.type = :type) " +
                   "AND (:status IS NULL OR t.status = :status) " +
                   "AND (:categoryId IS NULL OR t.category.id = :categoryId) " +
                   "AND (:competenceDateFrom IS NULL OR t.competenceDate >= :competenceDateFrom) " +
                   "AND (:competenceDateTo IS NULL OR t.competenceDate <= :competenceDateTo) " +
                   "AND (:paymentDateFrom IS NULL OR t.paymentDate >= :paymentDateFrom) " +
                   "AND (:paymentDateTo IS NULL OR t.paymentDate <= :paymentDateTo) " +
                   "AND (:amountMin IS NULL OR t.amount >= :amountMin) " +
                   "AND (:amountMax IS NULL OR t.amount <= :amountMax) " +
                   "AND (:searchText IS NULL OR LOWER(t.description) LIKE LOWER(CONCAT('%', :searchText, '%')) " +
                   "     OR LOWER(COALESCE(t.notes, '')) LIKE LOWER(CONCAT('%', :searchText, '%'))) " +
                   "AND (:includeCancelled = TRUE OR t.status <> com.cashcontrol.api.domain.entity.TransactionStatus.CANCELLED)",
           countQuery = "SELECT COUNT(t) FROM Transaction t " +
                        "WHERE t.userId = :userId " +
                        "AND (:accountId IS NULL OR t.account.id = :accountId) " +
                        "AND (:type IS NULL OR t.type = :type) " +
                        "AND (:status IS NULL OR t.status = :status) " +
                        "AND (:categoryId IS NULL OR t.category.id = :categoryId) " +
                        "AND (:competenceDateFrom IS NULL OR t.competenceDate >= :competenceDateFrom) " +
                        "AND (:competenceDateTo IS NULL OR t.competenceDate <= :competenceDateTo) " +
                        "AND (:paymentDateFrom IS NULL OR t.paymentDate >= :paymentDateFrom) " +
                        "AND (:paymentDateTo IS NULL OR t.paymentDate <= :paymentDateTo) " +
                        "AND (:amountMin IS NULL OR t.amount >= :amountMin) " +
                        "AND (:amountMax IS NULL OR t.amount <= :amountMax) " +
                        "AND (:searchText IS NULL OR LOWER(t.description) LIKE LOWER(CONCAT('%', :searchText, '%')) " +
                        "     OR LOWER(COALESCE(t.notes, '')) LIKE LOWER(CONCAT('%', :searchText, '%'))) " +
                        "AND (:includeCancelled = TRUE OR t.status <> com.cashcontrol.api.domain.entity.TransactionStatus.CANCELLED)")
    Page<Transaction> findWithFilters(
            @Param("userId") UUID userId,
            @Param("accountId") UUID accountId,
            @Param("type") TransactionType type,
            @Param("status") TransactionStatus status,
            @Param("categoryId") UUID categoryId,
            @Param("competenceDateFrom") LocalDate competenceDateFrom,
            @Param("competenceDateTo") LocalDate competenceDateTo,
            @Param("paymentDateFrom") LocalDate paymentDateFrom,
            @Param("paymentDateTo") LocalDate paymentDateTo,
            @Param("amountMin") BigDecimal amountMin,
            @Param("amountMax") BigDecimal amountMax,
            @Param("searchText") String searchText,
            @Param("includeCancelled") boolean includeCancelled,
            Pageable pageable);

    @Modifying
    @Query("UPDATE Transaction t SET t.status = com.cashcontrol.api.domain.entity.TransactionStatus.OVERDUE " +
           "WHERE t.userId = :userId " +
           "AND t.status = com.cashcontrol.api.domain.entity.TransactionStatus.PENDING " +
           "AND t.paymentDate IS NOT NULL AND t.paymentDate < :today")
    int markOverdueForUser(@Param("userId") UUID userId, @Param("today") LocalDate today);

    @Modifying
    @Query("UPDATE Transaction t SET t.status = com.cashcontrol.api.domain.entity.TransactionStatus.OVERDUE " +
           "WHERE t.status = com.cashcontrol.api.domain.entity.TransactionStatus.PENDING " +
           "AND t.paymentDate IS NOT NULL AND t.paymentDate < :today")
    int markOverdueAll(@Param("today") LocalDate today);
}
