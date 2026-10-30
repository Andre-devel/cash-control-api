package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description = "Fatura fechada do cartão no banco, para escolher qual importar")
public record BankBillResponse(

        @Schema(description = "Id da fatura na Pluggy. Devolver na prévia.")
        String billId,

        @Schema(description = "Vencimento", example = "2026-10-07")
        LocalDate dueDate,

        @Schema(description = "Total declarado pelo banco", example = "1754.63")
        BigDecimal totalAmount
) {}
