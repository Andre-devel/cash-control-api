package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.AuditOutcome;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditOutcomeRepository extends LookupEntityRepository<AuditOutcome> {
}