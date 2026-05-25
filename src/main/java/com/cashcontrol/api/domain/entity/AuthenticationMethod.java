package com.cashcontrol.api.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "authentication_methods")
@NoArgsConstructor
public class AuthenticationMethod extends BaseLookupEntity {
}