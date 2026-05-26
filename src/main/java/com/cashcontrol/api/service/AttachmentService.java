package com.cashcontrol.api.service;

import com.cashcontrol.api.dto.response.AttachmentResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface AttachmentService {

    List<AttachmentResponse> attach(UUID transactionId, MultipartFile[] files, UUID userId);

    List<AttachmentResponse> getAttachments(UUID transactionId, UUID userId);

    void deleteAttachment(UUID attachmentId, UUID userId);
}
