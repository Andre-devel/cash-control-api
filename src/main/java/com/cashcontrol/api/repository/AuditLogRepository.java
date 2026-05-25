package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    Page<AuditLog> findByTargetUserIdOrderByCreatedAtDesc(UUID targetUserId, Pageable pageable);

    Page<AuditLog> findByEventTypeIdAndCreatedAtBetween(UUID eventTypeId, Instant from, Instant to, Pageable pageable);

    Page<AuditLog> findByActorUserIdAndCreatedAtBetween(UUID actorUserId, Instant from, Instant to, Pageable pageable);

    @Query("SELECT COUNT(al) FROM AuditLog al JOIN al.eventType et WHERE et.slug = :slug AND al.createdAt > :since")
    long countByEventTypeSlugAndCreatedAtAfter(@Param("slug") String slug, @Param("since") Instant since);

    @Query(value = "SELECT al FROM AuditLog al LEFT JOIN al.actorUser actor LEFT JOIN al.targetUser target " +
                   "WHERE (:eventTypeId IS NULL OR al.eventType.id = :eventTypeId) " +
                   "AND (:actorId IS NULL OR actor.id = :actorId) " +
                   "AND (:targetId IS NULL OR target.id = :targetId) " +
                   "AND (:from IS NULL OR al.createdAt >= :from) " +
                   "AND (:to IS NULL OR al.createdAt <= :to) " +
                   "AND (:outcomeId IS NULL OR al.outcome.id = :outcomeId)",
           countQuery = "SELECT COUNT(al) FROM AuditLog al LEFT JOIN al.actorUser actor LEFT JOIN al.targetUser target " +
                       "WHERE (:eventTypeId IS NULL OR al.eventType.id = :eventTypeId) " +
                       "AND (:actorId IS NULL OR actor.id = :actorId) " +
                       "AND (:targetId IS NULL OR target.id = :targetId) " +
                       "AND (:from IS NULL OR al.createdAt >= :from) " +
                       "AND (:to IS NULL OR al.createdAt <= :to) " +
                       "AND (:outcomeId IS NULL OR al.outcome.id = :outcomeId)")
    Page<AuditLog> findWithFilters(@Param("eventTypeId") UUID eventTypeId,
                                    @Param("actorId") UUID actorId,
                                    @Param("targetId") UUID targetId,
                                    @Param("from") Instant from,
                                    @Param("to") Instant to,
                                    @Param("outcomeId") UUID outcomeId,
                                    Pageable pageable);
}