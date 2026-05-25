package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.Invoice;
import com.cashcontrol.api.domain.entity.InvoiceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    Optional<Invoice> findByCreditCard_IdAndReferenceMonth(UUID creditCardId, String referenceMonth);

    Optional<Invoice> findByIdAndUserId(UUID id, UUID userId);

    List<Invoice> findAllByUserIdAndDueDateLessThanEqualAndStatusIn(
            UUID userId, LocalDate dueDate, List<InvoiceStatus> statuses);
}
