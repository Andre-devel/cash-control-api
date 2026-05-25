package com.cashcontrol.api.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "account_statuses")
@NoArgsConstructor
public class AccountStatus extends BaseLookupEntity {
}