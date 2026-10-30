package com.cashcontrol.api.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Uma aplicação de renda fixa dentro de uma conta INVESTMENT. Ver o cabeçalho da V37 para como
 * ela se relaciona com o saldo da conta.
 */
@Entity
@Table(name = "fixed_income_holdings")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class FixedIncomeHolding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    @Setter(lombok.AccessLevel.NONE)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 30)
    private FixedIncomeKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "indexer", length = 20)
    private FixedIncomeIndexer indexer;

    @Column(name = "rate", precision = 9, scale = 4)
    private BigDecimal rate;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "issuer", length = 100)
    private String issuer;

    @Column(name = "tax_exempt", nullable = false)
    private boolean taxExempt;

    @Enumerated(EnumType.STRING)
    @Column(name = "liquidity", nullable = false, length = 20)
    private FixedIncomeLiquidity liquidity;

    @Column(name = "applied_at", nullable = false)
    private LocalDate appliedAt;

    @Column(name = "maturity_date")
    private LocalDate maturityDate;

    @Column(name = "principal", nullable = false, precision = 19, scale = 2)
    private BigDecimal principal;

    /** Principal ainda aplicado: cai na proporção de cada resgate parcial. */
    @Column(name = "remaining_principal", nullable = false, precision = 19, scale = 2)
    private BigDecimal remainingPrincipal;

    @Column(name = "treasury_bond_type", length = 60)
    private String treasuryBondType;

    @Column(name = "treasury_quantity", precision = 19, scale = 8)
    private BigDecimal treasuryQuantity;

    @Column(name = "manual_value", precision = 19, scale = 2)
    private BigDecimal manualValue;

    @Column(name = "manual_value_date")
    private LocalDate manualValueDate;

    @Column(name = "funding_transfer_group_id")
    private UUID fundingTransferGroupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FixedIncomeStatus status = FixedIncomeStatus.ACTIVE;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @CreationTimestamp
    @Setter(lombok.AccessLevel.NONE)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
