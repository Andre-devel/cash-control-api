package com.cashcontrol.api.domain.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "login_attempts")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class LoginAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    @Setter(lombok.AccessLevel.NONE)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // Plain UUID field (nullable) — not a @ManyToOne, supports null for unknown-email attempts
    @Column(name = "user_id")
    private UUID userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auth_method_id")
    private AuthenticationMethod authMethod;

    @Column(name = "ip_address_masked", nullable = false, length = 45)
    private String ipAddressMasked;

    @Column(name = "user_agent_truncated", length = 512)
    private String userAgentTruncated;

    @Column(name = "was_successful", nullable = false)
    private boolean wasSuccessful;

    // Internal classification only — NEVER returned to callers (anti-enumeration)
    @JsonIgnore
    @Column(name = "failure_context", length = 50)
    private String failureContext;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt = Instant.now();
}