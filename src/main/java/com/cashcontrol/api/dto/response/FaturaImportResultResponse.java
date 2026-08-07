package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Resultado da confirmação de uma importação de fatura")
public record FaturaImportResultResponse(

        @Schema(description = "Lançamentos criados a partir das linhas do PDF", example = "13")
        int imported,

        @Schema(description = """
                Parcelas seguintes geradas junto, nas faturas dos meses à frente.

                Uma linha "Parcela 04 de 05" cria também a parcela 5 na fatura do mês \
                seguinte. As parcelas anteriores à do PDF nunca são criadas.""",
                example = "1")
        int futureInstallments,

        @Schema(description = "Linhas ignoradas por já existirem na fatura do mês", example = "0")
        int skippedDuplicates,

        @Schema(description = "Linhas rejeitadas na validação", example = "0")
        int failed,

        @Schema(description = "Motivo de cada linha rejeitada")
        List<ImportRowError> errors
) {}
