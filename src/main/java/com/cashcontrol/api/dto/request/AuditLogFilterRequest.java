package com.cashcontrol.api.dto.request;

import java.time.Instant;
import java.util.UUID;

public record AuditLogFilterRequest(
        String eventTypeSlug,
        UUID actorId,
        UUID targetId,
        Instant from,
        Instant to,
        String outcomeSlug
) {}
