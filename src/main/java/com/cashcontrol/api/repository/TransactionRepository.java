package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
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
import java.util.Collection;
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

    List<Transaction> findAllByRecurrenceRule_Id(UUID recurrenceRuleId);

    List<Transaction> findAllByRecurrenceRule_IdAndStatusIn(UUID recurrenceRuleId, List<TransactionStatus> statuses);

    // Um parcelamento é UMA compra fatiada, não N compras. Com :groupInstallments = TRUE a
    // série entra na listagem por uma única linha — a parcela de menor número entre as que
    // sobrevivem aos filtros de status/data. O subselect repete esses filtros para que a
    // série continue aparecendo uma vez quando o usuário recorta um período no meio dela.
    // A quitação antecipada (installmentNumber IS NULL) é um pagamento à parte e continua
    // com linha própria.
    String INSTALLMENT_GROUPING = """
            AND (:groupInstallments = FALSE
                 OR t.installmentSeries IS NULL
                 OR t.installmentNumber IS NULL
                 OR t.installmentNumber = (
                        SELECT MIN(i.installmentNumber) FROM Transaction i
                        WHERE i.installmentSeries.id = t.installmentSeries.id
                          AND i.installmentNumber IS NOT NULL
                          AND (:status IS NULL OR i.status = :status)
                          AND (:includeCancelled = TRUE OR i.status <> com.cashcontrol.api.domain.entity.TransactionStatus.CANCELLED)
                          AND (CAST(:competenceDateFrom AS LocalDate) IS NULL OR i.competenceDate >= :competenceDateFrom)
                          AND (CAST(:competenceDateTo AS LocalDate) IS NULL OR i.competenceDate <= :competenceDateTo)))
            """;

    String FILTER_PREDICATES = """
            WHERE t.userId = :userId
            AND (:accountId IS NULL OR t.account.id = :accountId)
            AND (:type IS NULL OR t.type = :type)
            AND (:status IS NULL OR t.status = :status)
            AND (:categoryId IS NULL OR t.category.id = :categoryId)
            AND (CAST(:competenceDateFrom AS LocalDate) IS NULL OR t.competenceDate >= :competenceDateFrom)
            AND (CAST(:competenceDateTo AS LocalDate) IS NULL OR t.competenceDate <= :competenceDateTo)
            AND (CAST(:paymentDateFrom AS LocalDate) IS NULL OR t.paymentDate >= :paymentDateFrom)
            AND (CAST(:paymentDateTo AS LocalDate) IS NULL OR t.paymentDate <= :paymentDateTo)
            AND (:amountMin IS NULL OR t.amount >= :amountMin)
            AND (:amountMax IS NULL OR t.amount <= :amountMax)
            AND (COALESCE(:searchText, '') = '' OR LOWER(t.description) LIKE LOWER(CONCAT('%', CAST(:searchText AS String), '%'))
                 OR LOWER(COALESCE(t.notes, '')) LIKE LOWER(CONCAT('%', CAST(:searchText AS String), '%')))
            AND (:includeCancelled = TRUE OR t.status <> com.cashcontrol.api.domain.entity.TransactionStatus.CANCELLED)
            AND (:paymentMethod IS NULL OR t.paymentMethod.slug = :paymentMethod)
            """;

    @Query(value = "SELECT t FROM Transaction t " + FILTER_PREDICATES + INSTALLMENT_GROUPING,
           countQuery = "SELECT COUNT(t) FROM Transaction t " + FILTER_PREDICATES + INSTALLMENT_GROUPING)
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
            @Param("paymentMethod") PaymentMethodSlug paymentMethod,
            @Param("groupInstallments") boolean groupInstallments,
            Pageable pageable);

    /**
     * Agregados por série usados para montar a linha colapsada do parcelamento:
     * {@code [seriesId, somaNaoCancelada, qtdPagas, qtdNaoCanceladas, qtdVencidas, ultimoPagamento]}.
     * Uma consulta só para a página inteira, para não cair em N+1.
     */
    @Query("SELECT i.installmentSeries.id, " +
           "COALESCE(SUM(CASE WHEN i.status <> com.cashcontrol.api.domain.entity.TransactionStatus.CANCELLED THEN i.amount ELSE 0 END), 0), " +
           "SUM(CASE WHEN i.status = com.cashcontrol.api.domain.entity.TransactionStatus.PAID THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN i.status <> com.cashcontrol.api.domain.entity.TransactionStatus.CANCELLED THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN i.status = com.cashcontrol.api.domain.entity.TransactionStatus.OVERDUE THEN 1 ELSE 0 END), " +
           "MAX(i.paymentDate) " +
           "FROM Transaction i " +
           "WHERE i.installmentSeries.id IN :seriesIds AND i.installmentNumber IS NOT NULL " +
           "GROUP BY i.installmentSeries.id")
    List<Object[]> aggregateInstallmentSeries(@Param("seriesIds") Collection<UUID> seriesIds);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Transaction t SET t.status = com.cashcontrol.api.domain.entity.TransactionStatus.OVERDUE " +
           "WHERE t.userId = :userId " +
           "AND t.status = com.cashcontrol.api.domain.entity.TransactionStatus.PENDING " +
           "AND t.paymentDate IS NOT NULL AND t.paymentDate < :today")
    int markOverdueForUser(@Param("userId") UUID userId, @Param("today") LocalDate today);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Transaction t SET t.status = com.cashcontrol.api.domain.entity.TransactionStatus.OVERDUE " +
           "WHERE t.status = com.cashcontrol.api.domain.entity.TransactionStatus.PENDING " +
           "AND t.paymentDate IS NOT NULL AND t.paymentDate < :today")
    int markOverdueAll(@Param("today") LocalDate today);

    // ── Dashboard queries ─────────────────────────────────────────────────────

    @Query("SELECT COALESCE(SUM(CASE " +
           "WHEN t.type = com.cashcontrol.api.domain.entity.TransactionType.INCOME " +
           "  OR t.type = com.cashcontrol.api.domain.entity.TransactionType.REFUND THEN t.amount " +
           "WHEN t.type = com.cashcontrol.api.domain.entity.TransactionType.EXPENSE THEN -t.amount " +
           "ELSE t.amount END), 0) " +
           "FROM Transaction t " +
           "WHERE t.userId = :userId " +
           "AND t.status = com.cashcontrol.api.domain.entity.TransactionStatus.PAID " +
           "AND t.account.archivedAt IS NULL AND t.account.deletedAt IS NULL " +
           "AND t.account.type <> :excludedType")
    BigDecimal sumTotalBalanceExcludingType(
            @Param("userId") UUID userId,
            @Param("excludedType") AccountType excludedType);

    @Query("SELECT COALESCE(SUM(CASE " +
           "WHEN t.type = com.cashcontrol.api.domain.entity.TransactionType.INCOME " +
           "  OR t.type = com.cashcontrol.api.domain.entity.TransactionType.REFUND THEN t.amount " +
           "WHEN t.type = com.cashcontrol.api.domain.entity.TransactionType.EXPENSE THEN -t.amount " +
           "ELSE t.amount END), 0) " +
           "FROM Transaction t " +
           "WHERE t.userId = :userId " +
           "AND t.status = com.cashcontrol.api.domain.entity.TransactionStatus.PAID " +
           "AND t.account.archivedAt IS NULL AND t.account.deletedAt IS NULL")
    BigDecimal sumTotalNetWorth(@Param("userId") UUID userId);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t " +
           "WHERE t.userId = :userId " +
           "AND t.type = :type " +
           "AND t.status = com.cashcontrol.api.domain.entity.TransactionStatus.PAID " +
           "AND t.paymentDate >= :from AND t.paymentDate <= :to " +
           "AND (:accountId IS NULL OR t.account.id = :accountId)")
    BigDecimal sumPaidByTypeAndPaymentDateRange(
            @Param("userId") UUID userId,
            @Param("type") TransactionType type,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("accountId") UUID accountId);

    @Query("SELECT t.category.id, SUM(t.amount) FROM Transaction t " +
           "WHERE t.userId = :userId " +
           "AND t.status = com.cashcontrol.api.domain.entity.TransactionStatus.PAID " +
           "AND t.type = :type " +
           "AND t.paymentDate >= :from AND t.paymentDate <= :to " +
           "AND (:accountId IS NULL OR t.account.id = :accountId) " +
           "AND t.category IS NOT NULL " +
           "GROUP BY t.category.id " +
           "ORDER BY SUM(t.amount) DESC")
    List<Object[]> findCategoryBreakdown(
            @Param("userId") UUID userId,
            @Param("type") TransactionType type,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("accountId") UUID accountId);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t " +
           "WHERE t.userId = :userId " +
           "AND t.type = :type " +
           "AND t.status = com.cashcontrol.api.domain.entity.TransactionStatus.PAID " +
           "AND t.paymentDate >= :from AND t.paymentDate <= :to " +
           "AND (:accountId IS NULL OR t.account.id = :accountId) " +
           "AND t.category IS NULL")
    BigDecimal sumUncategorized(
            @Param("userId") UUID userId,
            @Param("type") TransactionType type,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("accountId") UUID accountId);

    @Query("SELECT t FROM Transaction t " +
           "WHERE t.userId = :userId " +
           "AND t.status IN :statuses " +
           "AND t.paymentDate <= :deadline " +
           "ORDER BY t.paymentDate ASC")
    List<Transaction> findUpcomingBills(
            @Param("userId") UUID userId,
            @Param("statuses") List<TransactionStatus> statuses,
            @Param("deadline") LocalDate deadline,
            Pageable pageable);

    @Query("SELECT t FROM Transaction t " +
           "WHERE t.userId = :userId " +
           "AND t.type = com.cashcontrol.api.domain.entity.TransactionType.EXPENSE " +
           "AND t.status = com.cashcontrol.api.domain.entity.TransactionStatus.PAID " +
           "AND t.paymentDate >= :from AND t.paymentDate <= :to " +
           "ORDER BY t.amount DESC")
    List<Transaction> findLargestExpenses(
            @Param("userId") UUID userId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            Pageable pageable);

    @Query("SELECT t FROM Transaction t " +
           "WHERE t.userId = :userId " +
           "AND t.status <> com.cashcontrol.api.domain.entity.TransactionStatus.CANCELLED " +
           "AND t.account.archivedAt IS NULL AND t.account.deletedAt IS NULL " +
           "ORDER BY t.competenceDate DESC, t.createdAt DESC")
    List<Transaction> findRecentTransactions(
            @Param("userId") UUID userId,
            Pageable pageable);

    @Query("SELECT COALESCE(SUM(CASE " +
           "WHEN t.type = com.cashcontrol.api.domain.entity.TransactionType.INCOME " +
           "  OR t.type = com.cashcontrol.api.domain.entity.TransactionType.REFUND THEN t.amount " +
           "WHEN t.type = com.cashcontrol.api.domain.entity.TransactionType.EXPENSE THEN -t.amount " +
           "ELSE t.amount END), 0) " +
           "FROM Transaction t " +
           "WHERE t.userId = :userId " +
           "AND t.status = com.cashcontrol.api.domain.entity.TransactionStatus.PAID " +
           "AND t.account.archivedAt IS NULL AND t.account.deletedAt IS NULL " +
           "AND t.paymentDate <= :upTo")
    BigDecimal sumNetWorthUpTo(
            @Param("userId") UUID userId,
            @Param("upTo") LocalDate upTo);

    @Query(value = "SELECT TO_CHAR(payment_date, 'YYYY-MM') AS month, type, COALESCE(SUM(amount), 0) AS total " +
                   "FROM transactions " +
                   "WHERE user_id = :userId AND status = 'PAID' AND type IN ('INCOME', 'EXPENSE') " +
                   "AND payment_date BETWEEN :from AND :to " +
                   "AND (CAST(:accountId AS uuid) IS NULL OR account_id = CAST(:accountId AS uuid)) " +
                   "GROUP BY TO_CHAR(payment_date, 'YYYY-MM'), type " +
                   "ORDER BY TO_CHAR(payment_date, 'YYYY-MM') ASC",
           nativeQuery = true)
    List<Object[]> findMonthlyIncomeExpense(
            @Param("userId") UUID userId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("accountId") UUID accountId);

    // ── Category suggestion queries ───────────────────────────────────────────

    @Query("SELECT t.category.id, t.subcategory.id, COUNT(t) FROM Transaction t " +
           "WHERE t.userId = :userId AND t.category IS NOT NULL " +
           "AND t.status <> com.cashcontrol.api.domain.entity.TransactionStatus.CANCELLED " +
           "AND LOWER(t.description) LIKE LOWER(CONCAT('%', :text, '%')) " +
           "GROUP BY t.category.id, t.subcategory.id " +
           "ORDER BY COUNT(t) DESC")
    List<Object[]> findTopCategoriesByDescriptionText(
            @Param("userId") UUID userId,
            @Param("text") String text,
            org.springframework.data.domain.Pageable pageable);

    @Query("SELECT t.category.id, t.subcategory.id, COUNT(t) FROM Transaction t " +
           "WHERE t.userId = :userId AND t.category IS NOT NULL " +
           "AND t.status <> com.cashcontrol.api.domain.entity.TransactionStatus.CANCELLED " +
           "GROUP BY t.category.id, t.subcategory.id " +
           "ORDER BY COUNT(t) DESC")
    List<Object[]> findTopCategoriesByFrequency(
            @Param("userId") UUID userId,
            org.springframework.data.domain.Pageable pageable);
}
