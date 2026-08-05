package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.StatementFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

@Schema(description = "Prévia da importação de um extrato. Nada foi gravado.")
public record ImportPreviewResponse(

        @Schema(description = "Nome do arquivo enviado", example = "Extrato-06-08-2024-a-05-08-2026-CSV.csv")
        String fileName,

        @Schema(description = "Formato usado na leitura", example = "INTER_CSV")
        StatementFormat format,

        @Schema(description = "Identificação da conta no cabeçalho do extrato, para o usuário conferir "
                              + "que escolheu a conta certa", example = "123456789")
        String sourceAccountLabel,

        @Schema(description = "Início do período declarado no arquivo", example = "2024-08-06")
        LocalDate periodStart,

        @Schema(description = "Fim do período declarado no arquivo", example = "2026-08-05")
        LocalDate periodEnd,

        @Schema(description = "Total de lançamentos lidos", example = "706")
        int totalRows,

        @Schema(description = "Lançamentos ainda não importados nesta conta", example = "700")
        int importableCount,

        @Schema(description = "Lançamentos que já existem nesta conta e serão ignorados", example = "6")
        int duplicateCount,

        @Schema(description = "Lançamentos com histórico desconhecido, classificados pelo sinal do valor",
                example = "2")
        int warningCount,

        @Schema(description = "Lançamentos lidos, na ordem do arquivo")
        List<ImportPreviewRow> rows,

        @Schema(description = "Linhas que não puderam ser lidas")
        List<ImportRowError> errors
) {}
