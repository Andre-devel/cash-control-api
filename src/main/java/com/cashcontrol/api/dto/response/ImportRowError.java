package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Linha do arquivo que não pôde ser lida ou importada")
public record ImportRowError(

        @Schema(description = "Número da linha no arquivo enviado (1-based)", example = "42")
        int lineNumber,

        @Schema(description = "Motivo da falha, em português", example = "Valor inválido: '1.2O3,45'")
        String message
) {}
