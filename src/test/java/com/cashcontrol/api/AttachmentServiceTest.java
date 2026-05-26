package com.cashcontrol.api;

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.entity.Attachment;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.response.AttachmentResponse;
import com.cashcontrol.api.repository.AttachmentRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.AttachmentServiceImpl;
import com.cashcontrol.api.storage.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttachmentServiceTest {

    @Mock private AttachmentRepository attachmentRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private StoragePort storagePort;
    @Mock private AppProperties appProperties;
    @InjectMocks private AttachmentServiceImpl attachmentService;

    private UUID userId;
    private UUID transactionId;
    private Transaction transaction;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        transactionId = UUID.randomUUID();

        transaction = new Transaction();
        ReflectionTestUtils.setField(transaction, "id", transactionId);

        AppProperties.Attachments config = new AppProperties.Attachments();
        when(appProperties.getAttachments()).thenReturn(config);
    }

    // ── attach ────────────────────────────────────────────────────────────────

    @Test
    void attach_validPdf_success() {
        when(transactionRepository.findByIdAndUserId(transactionId, userId))
                .thenReturn(Optional.of(transaction));
        when(attachmentRepository.countByTransaction_IdAndUserIdAndDeletedAtIsNull(transactionId, userId))
                .thenReturn(0L);
        when(storagePort.store(any())).thenReturn("uuid-storage-key");
        when(attachmentRepository.save(any())).thenAnswer(inv -> {
            Attachment a = inv.getArgument(0);
            ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
            a.setUploadedAt(Instant.now());
            return a;
        });

        MockMultipartFile file = new MockMultipartFile(
                "files", "receipt.pdf", "application/pdf", new byte[100]);

        List<AttachmentResponse> responses = attachmentService.attach(
                transactionId, new MultipartFile[]{file}, userId);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).originalFilename()).isEqualTo("receipt.pdf");
        assertThat(responses.get(0).mimeType()).isEqualTo("application/pdf");
        verify(storagePort).store(any());
        verify(attachmentRepository).save(any());
    }

    @Test
    void attach_validPng_success() {
        when(transactionRepository.findByIdAndUserId(transactionId, userId))
                .thenReturn(Optional.of(transaction));
        when(attachmentRepository.countByTransaction_IdAndUserIdAndDeletedAtIsNull(transactionId, userId))
                .thenReturn(0L);
        when(storagePort.store(any())).thenReturn("uuid-storage-key");
        when(attachmentRepository.save(any())).thenAnswer(inv -> {
            Attachment a = inv.getArgument(0);
            ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
            a.setUploadedAt(Instant.now());
            return a;
        });

        MockMultipartFile file = new MockMultipartFile(
                "files", "photo.png", "image/png", new byte[50]);

        List<AttachmentResponse> responses = attachmentService.attach(
                transactionId, new MultipartFile[]{file}, userId);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).mimeType()).isEqualTo("image/png");
    }

    @Test
    void attach_transactionNotFound_throws() {
        when(transactionRepository.findByIdAndUserId(transactionId, userId))
                .thenReturn(Optional.empty());

        MockMultipartFile file = new MockMultipartFile(
                "files", "f.pdf", "application/pdf", new byte[10]);

        assertThatThrownBy(() -> attachmentService.attach(transactionId, new MultipartFile[]{file}, userId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(transactionId.toString());
    }

    @Test
    void attach_limitExceeded_throws() {
        when(transactionRepository.findByIdAndUserId(transactionId, userId))
                .thenReturn(Optional.of(transaction));
        when(attachmentRepository.countByTransaction_IdAndUserIdAndDeletedAtIsNull(transactionId, userId))
                .thenReturn(5L); // at default max of 5

        MockMultipartFile file = new MockMultipartFile(
                "files", "f.pdf", "application/pdf", new byte[10]);

        assertThatThrownBy(() -> attachmentService.attach(transactionId, new MultipartFile[]{file}, userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("limit exceeded");
    }

    @Test
    void attach_unsupportedMimeType_throws() {
        when(transactionRepository.findByIdAndUserId(transactionId, userId))
                .thenReturn(Optional.of(transaction));
        when(attachmentRepository.countByTransaction_IdAndUserIdAndDeletedAtIsNull(transactionId, userId))
                .thenReturn(0L);

        MockMultipartFile file = new MockMultipartFile(
                "files", "virus.exe", "application/octet-stream", new byte[10]);

        assertThatThrownBy(() -> attachmentService.attach(transactionId, new MultipartFile[]{file}, userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Unsupported file type");
    }

    @Test
    void attach_nullContentType_throws() {
        when(transactionRepository.findByIdAndUserId(transactionId, userId))
                .thenReturn(Optional.of(transaction));
        when(attachmentRepository.countByTransaction_IdAndUserIdAndDeletedAtIsNull(transactionId, userId))
                .thenReturn(0L);

        MockMultipartFile file = new MockMultipartFile(
                "files", "f.bin", null, new byte[10]);

        assertThatThrownBy(() -> attachmentService.attach(transactionId, new MultipartFile[]{file}, userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Unsupported file type");
    }

    @Test
    void attach_fileTooLarge_throws() {
        when(transactionRepository.findByIdAndUserId(transactionId, userId))
                .thenReturn(Optional.of(transaction));
        when(attachmentRepository.countByTransaction_IdAndUserIdAndDeletedAtIsNull(transactionId, userId))
                .thenReturn(0L);

        // default maxFileSizeMb = 10; exceed it by 1 byte
        byte[] bigContent = new byte[10 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile(
                "files", "large.pdf", "application/pdf", bigContent);

        assertThatThrownBy(() -> attachmentService.attach(transactionId, new MultipartFile[]{file}, userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("size exceeds");
    }

    // ── getAttachments ────────────────────────────────────────────────────────

    @Test
    void getAttachments_success_returnsEmptyList() {
        when(transactionRepository.findByIdAndUserId(transactionId, userId))
                .thenReturn(Optional.of(transaction));
        when(attachmentRepository.findAllByTransaction_IdAndUserIdAndDeletedAtIsNull(transactionId, userId))
                .thenReturn(List.of());

        List<AttachmentResponse> result = attachmentService.getAttachments(transactionId, userId);

        assertThat(result).isEmpty();
    }

    @Test
    void getAttachments_transactionNotFound_throws() {
        when(transactionRepository.findByIdAndUserId(transactionId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> attachmentService.getAttachments(transactionId, userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── deleteAttachment ──────────────────────────────────────────────────────

    @Test
    void deleteAttachment_success() {
        UUID attachmentId = UUID.randomUUID();
        Attachment attachment = new Attachment();
        ReflectionTestUtils.setField(attachment, "id", attachmentId);
        attachment.setStorageKey("some-storage-key");
        attachment.setUserId(userId);

        when(attachmentRepository.findByIdAndUserIdAndDeletedAtIsNull(attachmentId, userId))
                .thenReturn(Optional.of(attachment));
        when(attachmentRepository.save(any())).thenReturn(attachment);

        attachmentService.deleteAttachment(attachmentId, userId);

        assertThat(attachment.getDeletedAt()).isNotNull();
        verify(storagePort).delete("some-storage-key");
        verify(attachmentRepository).save(attachment);
    }

    @Test
    void deleteAttachment_notFound_throws() {
        UUID attachmentId = UUID.randomUUID();
        when(attachmentRepository.findByIdAndUserIdAndDeletedAtIsNull(attachmentId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> attachmentService.deleteAttachment(attachmentId, userId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(attachmentId.toString());
    }
}
