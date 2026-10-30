package com.cashcontrol.api.domain.entity;

/**
 * @author Andre
 * 30 de Outubro. de 2026
 */

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Lançamento que a Pluggy avisou e o usuário ainda não confirmou. Guarda só a linha do extrato;
 * a classificação sai da prévia do import a cada leitura.
 */
@Entity
@Table(name = "bank_pending_transactions")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BankPendingTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    @Setter(lombok.AccessLevel.NONE)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "external_ref", nullable = false, updatable = false, length = 64)
    private String externalRef;

    @Column(name = "date", nullable = false, updatable = false)
    private LocalDate date;

    @Column(name = "history", nullable = false, updatable = false, length = 255)
    private String history;

    @Column(name = "description", nullable = false, updatable = false, length = 255)
    private String description;

    /** Com sinal: negativo saiu da conta. */
    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "action_token_hash", length = 64)
    private String actionTokenHash;

    @Column(name = "action_token_expires_at")
    private Instant actionTokenExpiresAt;

    @CreationTimestamp
    @Setter(lombok.AccessLevel.NONE)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "notified_at")
    private Instant notifiedAt;

    @Column(name = "discarded_at")
    private Instant discardedAt;
}
