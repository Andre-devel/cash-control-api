package com.cashcontrol.api.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "auth_origins")
@NoArgsConstructor
public class AuthOrigin extends BaseLookupEntity {
}