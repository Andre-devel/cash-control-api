package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description = "Fatura do cartão no banco, para escolher qual importar: as fechadas e a atual, em aberto")
public record BankBillResponse(

        @Schema(description = "Id da fatura na Pluggy. Devolver na prévia.")
        String billId,

        @Schema(description = "Vencimento", example = "2026-10-07")
        LocalDate dueDate,

        @Schema(description = "Total declarado pelo banco. Null na fatura em aberto.", example = "1754.63")
        BigDecimal totalAmount,

        @Schema(description = """
                true na fatura atual, ainda em aberto: o vencimento é estimado (um mês depois da última \
                fechada) e entram também os lançamentos pendentes, que ainda podem mudar.""")
        boolean open
) {}
