package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Resultado da confirmação de uma importação")
public record ImportResultResponse(

        @Schema(description = "Transações criadas", example = "698")
        int imported,

        @Schema(description = "Linhas ignoradas por já existirem nesta conta", example = "6")
        int skippedDuplicates,

        @Schema(description = "Linhas rejeitadas na validação", example = "2")
        int failed,

        @Schema(description = "Motivo de cada linha rejeitada")
        List<ImportRowError> errors
) {}
