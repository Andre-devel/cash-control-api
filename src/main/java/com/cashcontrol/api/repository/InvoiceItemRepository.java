package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.InvoiceItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvoiceItemRepository extends JpaRepository<InvoiceItem, UUID> {

    List<InvoiceItem> findAllByInvoice_IdAndCancelledAtIsNull(UUID invoiceId);

    Optional<InvoiceItem> findByIdAndUserId(UUID id, UUID userId);
}
