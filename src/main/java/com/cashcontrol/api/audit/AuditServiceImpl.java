package com.cashcontrol.api.audit;

import com.cashcontrol.api.domain.entity.AuditLog;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.dto.request.AuditLogFilterRequest;
import com.cashcontrol.api.dto.response.AuditLogResponse;
import com.cashcontrol.api.repository.AuditLogRepository;
import com.cashcontrol.api.repository.LookupCache;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.security.CorrelationIdHolder;
import com.cashcontrol.api.util.DataMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    private final AuditLogRepository auditLogRepository;
    private final LookupCache lookupCache;
    private final AuditMetadataSanitizer sanitizer;
    private final DataMasker dataMasker;
    private final UserRepository userRepository;

    @Lazy
    @Autowired
    private AuditServiceImpl self;

    @Override
    public void record(AuditEventSlug event, AuditOutcomeSlug outcome, UUID actorId, UUID targetId,
                       Map<String, Object> metadata) {
        // Capture request-scoped values in the calling thread (ScopedValues)
        String ip = dataMasker.maskIp(RequestContext.getIp());
        String ua = dataMasker.truncateUserAgent(RequestContext.getUserAgent(), 512);
        UUID correlationId = CorrelationIdHolder.get();

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // Defer until after the current transaction commits to avoid FK violations
            // when the referenced user/entity was created in the same transaction.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    self.persistAsync(event, outcome, actorId, targetId, metadata, ip, ua, correlationId);
                }
            });
        } else {
            self.persistAsync(event, outcome, actorId, targetId, metadata, ip, ua, correlationId);
        }
    }

    @Async
    public void persistAsync(AuditEventSlug event, AuditOutcomeSlug outcome, UUID actorId, UUID targetId,
                              Map<String, Object> metadata, String maskedIp, String truncatedUa,
                              UUID correlationId) {
        try {
            AuditLog entry = new AuditLog();
            entry.setEventType(lookupCache.requireAuditEventType(event.name()));
            entry.setOutcome(lookupCache.requireAuditOutcome(outcome.name()));
            entry.setIpAddressMasked(maskedIp);
            entry.setUserAgentTruncated(truncatedUa);
            entry.setCorrelationId(correlationId);
            entry.setMetadata(sanitizer.sanitize(metadata));

            if (actorId != null) {
                User actor = new User();
                // use entity reference proxy so no extra DB round-trip
                entry.setActorUser(userRepository.getReferenceById(actorId));
            }
            if (targetId != null) {
                entry.setTargetUser(userRepository.getReferenceById(targetId));
            }

            auditLogRepository.save(entry);
        } catch (Exception ex) {
            log.error("Audit persistence failed for event {} — error swallowed to protect caller", event, ex);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditLogResponse> queryAuditLogs(AuditLogFilterRequest filter, Pageable pageable) {
        UUID eventTypeId = null;
        if (filter.eventTypeSlug() != null && !filter.eventTypeSlug().isBlank()) {
            try {
                eventTypeId = lookupCache.requireAuditEventType(filter.eventTypeSlug()).getId();
            } catch (IllegalStateException ignored) {
                // unknown slug → no results
                return Page.empty(pageable);
            }
        }
        UUID outcomeId = null;
        if (filter.outcomeSlug() != null && !filter.outcomeSlug().isBlank()) {
            try {
                outcomeId = lookupCache.requireAuditOutcome(filter.outcomeSlug()).getId();
            } catch (IllegalStateException ignored) {
                return Page.empty(pageable);
            }
        }
        return auditLogRepository.findWithFilters(
                eventTypeId, filter.actorId(), filter.targetId(),
                filter.from(), filter.to(), outcomeId, pageable)
            .map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditLogResponse> getUserAuditTimeline(UUID userId, Pageable pageable) {
        return auditLogRepository.findByTargetUserIdOrderByCreatedAtDesc(userId, pageable)
            .map(this::toResponse);
    }

    private AuditLogResponse toResponse(AuditLog log) {
        return new AuditLogResponse(
            log.getId(),
            log.getEventType().getSlug(),
            log.getOutcome().getSlug(),
            log.getEventType().getSeverity(),
            log.getActorUser() != null ? log.getActorUser().getId() : null,
            log.getTargetUser() != null ? log.getTargetUser().getId() : null,
            log.getIpAddressMasked(),
            log.getCorrelationId() != null ? log.getCorrelationId().toString() : null,
            sanitizer.sanitize(log.getMetadata()),
            log.getCreatedAt()
        );
    }
}