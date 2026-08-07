package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.InvoiceImportFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "Prévia da importação de uma fatura de cartão. Nada foi gravado.")
public record FaturaImportPreviewResponse(

        @Schema(description = "Nome do arquivo enviado", example = "fatura-inter-2026-07.pdf")
        String fileName,

        @Schema(description = "Formato usado na leitura", example = "INTER_FATURA_PDF")
        InvoiceImportFormat format,

        @Schema(description = "Vencimento declarado na fatura", example = "2026-08-07")
        LocalDate dueDate,

        @Schema(description = "Mês de referência da fatura no sistema, deduzido do vencimento "
                              + "(o vencimento é sempre no mês seguinte ao fechamento)", example = "2026-07")
        String referenceMonth,

        @Schema(description = "Total da fatura declarado pelo banco, para o usuário conferir. "
                              + "Null quando não foi possível lê-lo.", example = "1617.29")
        BigDecimal totalAmount,

        @Schema(description = "Uma entrada por seção de cartão do PDF")
        List<FaturaImportGroupPreview> groups,

        @Schema(description = "Total de lançamentos importáveis somando todos os grupos", example = "13")
        int totalRows,

        @Schema(description = "Lançamentos que já existem na fatura deste mês e serão ignorados", example = "0")
        int duplicateCount,

        @Schema(description = "Linhas de crédito (pagamento da fatura, estorno) descartadas na leitura: "
                              + "não são despesas e não viram lançamento", example = "1")
        int excludedPaymentsCount,

        @Schema(description = "Linhas que não puderam ser lidas")
        List<ImportRowError> errors
) {}
