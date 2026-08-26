package com.cashcontrol.api.service;

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.entity.Attachment;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.response.AttachmentResponse;
import com.cashcontrol.api.repository.AttachmentRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.storage.StoragePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AttachmentServiceImpl implements AttachmentService {

    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/pdf", "image/png", "image/jpeg"
    );

    private final AttachmentRepository attachmentRepository;
    private final TransactionRepository transactionRepository;
    private final StoragePort storagePort;
    private final AppProperties appProperties;

    @Override
    @Transactional
    public void deleteAllForTransactions(Collection<UUID> transactionIds) {
        if (transactionIds.isEmpty()) {
            return;
        }

        List<Attachment> attachments = attachmentRepository.findAllByTransaction_IdIn(transactionIds);
        for (Attachment attachment : attachments) {
            if (attachment.getDeletedAt() == null) {
                storagePort.delete(attachment.getStorageKey());
            }
        }
        attachmentRepository.deleteAll(attachments);
    }

    @Override
    @Transactional
    public List<AttachmentResponse> attach(UUID transactionId, MultipartFile[] files, UUID userId) {
        Transaction transaction = transactionRepository.findByIdAndUserId(transactionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + transactionId));

        long maxFileSizeBytes = (long) appProperties.getAttachments().getMaxFileSizeMb() * 1024 * 1024;
        int maxPerTransaction = appProperties.getAttachments().getMaxPerTransaction();

        long currentCount = attachmentRepository.countByTransaction_IdAndUserIdAndDeletedAtIsNull(transactionId, userId);
        if (currentCount + files.length > maxPerTransaction) {
            throw new BusinessRuleException(
                    "Limite de anexos excedido. Máximo de " + maxPerTransaction + " anexos por transação.");
        }

        return Arrays.stream(files)
                .map(file -> storeFile(file, transaction, userId, maxFileSizeBytes))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttachmentResponse> getAttachments(UUID transactionId, UUID userId) {
        transactionRepository.findByIdAndUserId(transactionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + transactionId));

        return attachmentRepository.findAllByTransaction_IdAndUserIdAndDeletedAtIsNull(transactionId, userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public void deleteAttachment(UUID attachmentId, UUID userId) {
        Attachment attachment = attachmentRepository.findByIdAndUserIdAndDeletedAtIsNull(attachmentId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Attachment not found: " + attachmentId));

        attachment.setDeletedAt(Instant.now());
        attachmentRepository.save(attachment);
        storagePort.delete(attachment.getStorageKey());
    }

    private AttachmentResponse storeFile(MultipartFile file, Transaction transaction, UUID userId, long maxFileSizeBytes) {
        String mimeType = file.getContentType();
        if (mimeType == null || !ALLOWED_MIME_TYPES.contains(mimeType)) {
            throw new BusinessRuleException(
                    "Tipo de arquivo não suportado: " + mimeType + ". Permitidos: PDF, PNG, JPEG.");
        }

        if (file.getSize() > maxFileSizeBytes) {
            throw new BusinessRuleException(
                    "O arquivo excede o tamanho máximo permitido de " +
                    appProperties.getAttachments().getMaxFileSizeMb() + " MB.");
        }

        String storageKey = storagePort.store(file);

        Attachment attachment = new Attachment();
        attachment.setUserId(userId);
        attachment.setTransaction(transaction);
        attachment.setOriginalFilename(file.getOriginalFilename() != null ? file.getOriginalFilename() : "attachment");
        attachment.setMimeType(mimeType);
        attachment.setFileSizeBytes(file.getSize());
        attachment.setStorageKey(storageKey);
        attachment.setUploadedAt(Instant.now());

        attachment = attachmentRepository.save(attachment);
        return toResponse(attachment);
    }

    private AttachmentResponse toResponse(Attachment attachment) {
        return new AttachmentResponse(
                attachment.getId(),
                attachment.getOriginalFilename(),
                attachment.getMimeType(),
                attachment.getFileSizeBytes(),
                attachment.getUploadedAt()
        );
    }
}
