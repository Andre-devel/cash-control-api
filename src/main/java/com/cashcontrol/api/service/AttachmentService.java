package com.cashcontrol.api.service;

import com.cashcontrol.api.dto.response.AttachmentResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AttachmentService {

    List<AttachmentResponse> attach(UUID transactionId, MultipartFile[] files, UUID userId);

    List<AttachmentResponse> getAttachments(UUID transactionId, UUID userId);

    void deleteAttachment(UUID attachmentId, UUID userId);

    // Hard-deletes every attachment row (live or soft-deleted) tied to these transactions,
    // cleaning up storage for the still-live ones. Called before a transaction/installment
    // delete to clear the attachments_transaction_id_fkey — cascading is fine here because
    // the transaction itself is gone, so nothing is left for the attachment to reference.
    void deleteAllForTransactions(Collection<UUID> transactionIds);
}
