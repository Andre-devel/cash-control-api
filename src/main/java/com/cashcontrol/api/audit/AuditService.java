package com.cashcontrol.api.audit;

import com.cashcontrol.api.dto.request.AuditLogFilterRequest;
import com.cashcontrol.api.dto.response.AuditLogResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Map;
import java.util.UUID;

public interface AuditService {

    void record(AuditEventSlug event, AuditOutcomeSlug outcome, UUID actorId, UUID targetId,
                Map<String, Object> metadata);

    default void record(AuditEventSlug event, AuditOutcomeSlug outcome, UUID actorId, UUID targetId) {
        record(event, outcome, actorId, targetId, null);
    }

    Page<AuditLogResponse> queryAuditLogs(AuditLogFilterRequest filter, Pageable pageable);

    Page<AuditLogResponse> getUserAuditTimeline(UUID userId, Pageable pageable);
}