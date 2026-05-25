package com.cashcontrol.api.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "lockout_types")
@NoArgsConstructor
public class LockoutType extends BaseLookupEntity {
}