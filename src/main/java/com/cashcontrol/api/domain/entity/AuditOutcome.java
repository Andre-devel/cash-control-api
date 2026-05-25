package com.cashcontrol.api.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "audit_outcomes")
@NoArgsConstructor
public class AuditOutcome extends BaseLookupEntity {
}