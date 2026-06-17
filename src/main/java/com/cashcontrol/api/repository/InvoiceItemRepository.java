package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.InvoiceItem;
import com.cashcontrol.api.domain.entity.InvoiceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvoiceItemRepository extends JpaRepository<InvoiceItem, UUID> {

    List<InvoiceItem> findAllByInvoice_IdAndCancelledAtIsNull(UUID invoiceId);

    Page<InvoiceItem> findAllByInvoice_IdAndCancelledAtIsNull(UUID invoiceId, Pageable pageable);

    Optional<InvoiceItem> findByIdAndUserId(UUID id, UUID userId);

    Optional<InvoiceItem> findByTransaction_Id(UUID transactionId);

    @Query("SELECT ii.category.id, ii.category.name, SUM(ii.amount) as total " +
           "FROM InvoiceItem ii " +
           "WHERE ii.invoice.creditCard.id = :cardId AND ii.userId = :userId " +
           "AND ii.cancelledAt IS NULL " +
           "AND (:from IS NULL OR ii.competenceDate >= :from) " +
           "AND (:to IS NULL OR ii.competenceDate <= :to) " +
           "AND ii.category IS NOT NULL " +
           "GROUP BY ii.category.id, ii.category.name " +
           "ORDER BY total DESC")
    List<Object[]> findSpendingByCategory(@Param("cardId") UUID cardId, @Param("userId") UUID userId,
                                          @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COALESCE(SUM(ii.amount), 0) FROM InvoiceItem ii " +
           "WHERE ii.invoice.creditCard.id = :cardId " +
           "AND ii.cancelledAt IS NULL " +
           "AND ii.invoice.status IN :statuses")
    BigDecimal sumAmountByCardIdAndInvoiceStatuses(@Param("cardId") UUID cardId,
                                                   @Param("statuses") List<InvoiceStatus> statuses);
}
