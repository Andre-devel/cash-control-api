package com.cashcontrol.api.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "oauth_providers")
@NoArgsConstructor
public class OauthProvider extends BaseLookupEntity {
}