package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID>, JpaSpecificationExecutor<AuditLog> {

    Page<AuditLog> findByTargetUserIdOrderByCreatedAtDesc(UUID targetUserId, Pageable pageable);

    Page<AuditLog> findByEventTypeIdAndCreatedAtBetween(UUID eventTypeId, Instant from, Instant to, Pageable pageable);

    Page<AuditLog> findByActorUserIdAndCreatedAtBetween(UUID actorUserId, Instant from, Instant to, Pageable pageable);

    @Query("SELECT COUNT(al) FROM AuditLog al JOIN al.eventType et WHERE et.slug = :slug AND al.createdAt > :since")
    long countByEventTypeSlugAndCreatedAtAfter(@Param("slug") String slug, @Param("since") Instant since);
}