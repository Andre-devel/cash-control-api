package com.cashcontrol.api.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "permission_categories")
@NoArgsConstructor
public class PermissionCategory extends BaseLookupEntity {
}