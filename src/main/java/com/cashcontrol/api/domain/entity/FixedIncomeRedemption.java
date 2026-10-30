package com.cashcontrol.api.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Um resgate, total ou parcial, com a divisão do valor e as transações que ele gravou. */
@Entity
@Table(name = "fixed_income_redemptions")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class FixedIncomeRedemption {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    @Setter(lombok.AccessLevel.NONE)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "holding_id", nullable = false, updatable = false)
    private FixedIncomeHolding holding;

    @Column(name = "redeemed_at", nullable = false)
    private LocalDate redeemedAt;

    @Column(name = "principal_portion", nullable = false, precision = 19, scale = 2)
    private BigDecimal principalPortion;

    @Column(name = "treasury_quantity", precision = 19, scale = 8)
    private BigDecimal treasuryQuantity;

    @Column(name = "gross", nullable = false, precision = 19, scale = 2)
    private BigDecimal gross;

    @Column(name = "income_tax", nullable = false, precision = 19, scale = 2)
    private BigDecimal incomeTax;

    @Column(name = "iof", nullable = false, precision = 19, scale = 2)
    private BigDecimal iof;

    @Column(name = "net", nullable = false, precision = 19, scale = 2)
    private BigDecimal net;

    @Column(name = "yield_transaction_id")
    private UUID yieldTransactionId;

    @Column(name = "transfer_group_id")
    private UUID transferGroupId;

    @CreationTimestamp
    @Setter(lombok.AccessLevel.NONE)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
