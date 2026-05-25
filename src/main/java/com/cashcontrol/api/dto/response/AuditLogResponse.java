package com.cashcontrol.api.dto.response;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditLogResponse(
        UUID id,
        String eventType,
        String outcome,
        String severity,
        UUID actorUserId,
        UUID targetUserId,
        String ipAddressMasked,
        String correlationId,
        Map<String, Object> metadata,
        Instant createdAt
) {}
