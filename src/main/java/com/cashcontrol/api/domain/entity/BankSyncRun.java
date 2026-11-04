package com.cashcontrol.api.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Uma leitura de item da Pluggy, para o histórico de sincronizações. Ver V44. */
@Entity
@Table(name = "bank_sync_runs")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BankSyncRun {

    public static final int ERROR_MESSAGE_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    @Setter(lombok.AccessLevel.NONE)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "connection_id")
    private UUID connectionId;

    @Column(name = "item_id", nullable = false, length = 64)
    private String itemId;

    @Column(name = "connector_name", length = 100)
    private String connectorName;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger", nullable = false, length = 20)
    private BankSyncTrigger trigger;

    @Column(name = "webhook_event", length = 64)
    private String webhookEvent;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    private BankSyncOutcome outcome;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;

    @Column(name = "item_status", length = 30)
    private String itemStatus;

    @Column(name = "item_last_updated_at")
    private Instant itemLastUpdatedAt;

    @Column(name = "accounts_read")
    private Integer accountsRead;

    @Column(name = "transactions_queued")
    private Integer transactionsQueued;

    @Column(name = "investments_created")
    private Integer investmentsCreated;

    @Column(name = "investments_redeemed")
    private Integer investmentsRedeemed;

    @Column(name = "investments_failed")
    private Integer investmentsFailed;

    @Column(name = "webhook_delay_minutes")
    private Long webhookDelayMinutes;

    @Column(name = "error_message", length = ERROR_MESSAGE_MAX)
    private String errorMessage;
}
