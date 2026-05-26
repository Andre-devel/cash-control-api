package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.Invoice;
import com.cashcontrol.api.domain.entity.InvoiceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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

    Page<Invoice> findAllByCreditCard_IdAndUserIdOrderByReferenceMonthDesc(UUID creditCardId, UUID userId, Pageable pageable);

    List<Invoice> findAllByUserIdAndDueDateLessThanEqualAndStatusIn(
            UUID userId, LocalDate dueDate, List<InvoiceStatus> statuses);
}
