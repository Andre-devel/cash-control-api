package com.cashcontrol.api.domain.entity;

/**
 * @author Andre
 * 4 de Novembro. de 2026
 */

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** Sugestão de recorrência que o usuário recusou; o padrão some de {@code GET /recurrences/suggestions}. */
@Entity
@Table(name = "recurrence_suggestion_dismissals")
@Getter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class RecurrenceSuggestionDismissal {

    @EmbeddedId
    @EqualsAndHashCode.Include
    private Key id;

    @Column(name = "dismissed_at", nullable = false, insertable = false, updatable = false)
    private Instant dismissedAt;

    @Embeddable
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {

        @Column(name = "user_id", nullable = false)
        private UUID userId;

        @Column(name = "pattern", nullable = false, length = 255)
        private String pattern;
    }
}
