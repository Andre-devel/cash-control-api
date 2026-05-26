package com.cashcontrol.api.dto.response;

import java.time.Instant;
import java.util.UUID;

public record AttachmentResponse(
        UUID id,
        String originalFilename,
        String mimeType,
        long fileSizeBytes,
        Instant uploadedAt
) {}
