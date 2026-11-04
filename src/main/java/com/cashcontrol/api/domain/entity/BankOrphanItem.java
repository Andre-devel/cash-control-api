package com.cashcontrol.api.domain.entity;

/**
 * @author Andre
 * 4 de Novembro. de 2026
 */

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Item da Pluggy que o webhook anunciou e não virou conexão (V50). Gravado só por SQL nativo. */
@Entity
@Table(name = "bank_orphan_items")
@Getter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BankOrphanItem {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "item_id", nullable = false, length = 64)
    private String itemId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "connector_name", length = 100)
    private String connectorName;

    @Column(name = "first_seen_at", nullable = false, insertable = false, updatable = false)
    private Instant firstSeenAt;
}
