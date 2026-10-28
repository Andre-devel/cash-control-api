package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.ReconciliationKind;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Uma correção proposta pela reconciliação de importações")
public record ReconciliationProposal(
        ReconciliationKind kind,

        @Schema(description = "Transação a converter; em SERIES_MERGE, a série que fica")
        UUID id,

        LocalDate date,

        String description,

        @Schema(description = "Valor do lançamento; em SERIES_MERGE, soma das parcelas das séries")
        BigDecimal amount,

        String accountName,

        @Schema(description = "O que vai acontecer, em pt-BR", example = "Vira pagamento da fatura Inter · 2026-09")
        String detail
) {}
