package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.AuditEventType;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditEventTypeRepository extends LookupEntityRepository<AuditEventType> {
}