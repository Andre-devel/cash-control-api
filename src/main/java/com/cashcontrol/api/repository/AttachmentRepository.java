package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    List<Attachment> findAllByTransaction_IdAndUserIdAndDeletedAtIsNull(UUID transactionId, UUID userId);

    Optional<Attachment> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    long countByTransaction_IdAndUserIdAndDeletedAtIsNull(UUID transactionId, UUID userId);
}
