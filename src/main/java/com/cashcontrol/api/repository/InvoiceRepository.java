package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.Invoice;
import com.cashcontrol.api.domain.entity.InvoiceStatus;
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
public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    Optional<Invoice> findByCreditCard_IdAndReferenceMonth(UUID creditCardId, String referenceMonth);

    Optional<Invoice> findByCreditCard_IdAndUserIdAndReferenceMonth(UUID creditCardId, UUID userId, String referenceMonth);

    Optional<Invoice> findByCreditCard_IdAndStatus(UUID creditCardId, InvoiceStatus status);

    Optional<Invoice> findByIdAndUserId(UUID id, UUID userId);

    /** Lote noturno de {@code Invoice.refreshTimelineStatus}: venceu sem ser paga. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Invoice i SET i.status = com.cashcontrol.api.domain.entity.InvoiceStatus.OVERDUE " +
           "WHERE i.status IN (com.cashcontrol.api.domain.entity.InvoiceStatus.OPEN, " +
           "                   com.cashcontrol.api.domain.entity.InvoiceStatus.CLOSED) " +
           "AND i.dueDate < :today")
    int markOverdue(@Param("today") LocalDate today);

    /** Lote noturno de {@code Invoice.refreshTimelineStatus}: passou do dia de fechamento. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Invoice i SET i.status = com.cashcontrol.api.domain.entity.InvoiceStatus.CLOSED " +
           "WHERE i.status = com.cashcontrol.api.domain.entity.InvoiceStatus.OPEN " +
           "AND i.closingDate < :today")
    int markClosed(@Param("today") LocalDate today);

    /**
     * Quanto do limite as faturas do cartão ainda ocupam: o saldo devedor de cada uma.
     *
     * <p>PARCIAL fica de fora: o restante dela já foi lançado como item rotativo na fatura
     * seguinte, e contá-la de novo ocuparia o limite em dobro. PAGA não deve nada.
     */
    @Query("SELECT COALESCE(SUM(i.totalAmount - i.paidAmount), 0) FROM Invoice i " +
           "WHERE i.creditCard.id = :cardId AND i.status IN :statuses")
    BigDecimal sumOutstandingByCardIdAndStatuses(@Param("cardId") UUID cardId,
                                                 @Param("statuses") List<InvoiceStatus> statuses);

    Page<Invoice> findAllByCreditCard_IdAndUserIdOrderByReferenceMonthDesc(UUID creditCardId, UUID userId, Pageable pageable);

    List<Invoice> findAllByUserIdAndDueDateLessThanEqualAndStatusIn(
            UUID userId, LocalDate dueDate, List<InvoiceStatus> statuses);
}
