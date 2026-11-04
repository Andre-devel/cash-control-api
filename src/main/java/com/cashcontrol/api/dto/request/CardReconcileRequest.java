package com.cashcontrol.api.dto.request;

/**
 * @author Andre
 * 4 de Novembro. de 2026
 */

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Ajuste do cartão: despesa avulsa na fatura aberta. Sem descrição, usa a padrão. */
public record CardReconcileRequest(
        @NotNull @Digits(integer = 13, fraction = 2) @DecimalMin("0.01") BigDecimal amount,
        @Size(max = 255) String description
) {}
