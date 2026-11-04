package com.cashcontrol.api.dto.response;

/**
 * @author Andre
 * 04 de Novembro. de 2026
 */

import com.cashcontrol.api.domain.entity.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Resultado da busca global: até 10 itens por grupo, só do usuário e sem apagados. */
public record SearchResponse(
        List<TransactionHit> transactions,
        List<Hit> accounts,
        List<Hit> creditCards,
        List<Hit> categories,
        List<Hit> recurrences,
        List<Hit> installments
) {

    public record Hit(
            UUID id,
            String title,
            @Schema(description = "Secondary line shown under the title; may be null.")
            String subtitle
    ) {
    }

    public record TransactionHit(
            UUID id,
            String title,
            @Schema(description = "Counterparty name, or the account name when there is none.")
            String subtitle,
            @Schema(description = "Competence date.")
            LocalDate date,
            BigDecimal amount,
            TransactionType type
    ) {
    }
}
