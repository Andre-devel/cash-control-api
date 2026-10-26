package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Fatura que uma linha de saída do extrato pode estar pagando")
public record ImportInvoiceOption(
        UUID id,

        @Schema(description = "Cartão e mês da fatura", example = "Inter - PF · 2026-09")
        String label,

        @Schema(description = "Total da fatura", example = "1754.74")
        BigDecimal totalAmount,

        @Schema(description = "Vencimento", example = "2026-10-10")
        LocalDate dueDate
) {}
