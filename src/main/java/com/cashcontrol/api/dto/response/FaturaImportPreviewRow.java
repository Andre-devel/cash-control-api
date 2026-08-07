package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Lançamento lido da fatura, ainda não gravado")
public record FaturaImportPreviewRow(

        @Schema(description = "Número da linha no texto extraído do PDF (1-based)", example = "59")
        int lineNumber,

        @Schema(description = "Hash da linha de origem. Devolver inalterado na confirmação.")
        String externalRef,

        @Schema(description = "Data da compra", example = "2026-04-04")
        LocalDate date,

        @Schema(description = "Descrição como veio no PDF, incluindo o sufixo de parcela",
                example = "SHOPEE *LarkSpComercio (Parcela 04 de 05)")
        String description,

        @Schema(description = "Valor absoluto da despesa", example = "55.19")
        BigDecimal amount,

        @Schema(description = "Número da parcela, quando o PDF declara uma", example = "4")
        Integer installmentNumber,

        @Schema(description = "Total de parcelas, quando o PDF declara uma", example = "5")
        Integer totalInstallments,

        @Schema(description = "Categoria sugerida pelas regras de categorização do usuário, quando alguma casou")
        UUID suggestedCategoryId,

        @Schema(description = "Nome da categoria sugerida", example = "Compras")
        String suggestedCategoryName,

        @Schema(description = "true quando esta linha já foi importada na fatura deste mês")
        boolean duplicate
) {}
