package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    List<Attachment> findAllByTransaction_IdAndUserIdAndDeletedAtIsNull(UUID transactionId, UUID userId);

    Optional<Attachment> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    long countByTransaction_IdAndUserIdAndDeletedAtIsNull(UUID transactionId, UUID userId);

    // Soft-deleted attachments still hold the FK to transactions, so deleting a
    // transaction requires checking every row, not just the live ones.
    long countByTransaction_IdIn(Collection<UUID> transactionIds);
}
