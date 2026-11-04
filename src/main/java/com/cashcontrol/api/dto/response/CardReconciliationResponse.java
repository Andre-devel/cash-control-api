package com.cashcontrol.api.dto.response;

/**
 * @author Andre
 * 4 de Novembro. de 2026
 */

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Uso do limite do cartão segundo o banco ao lado do que o app calcula. Os campos do banco e a
 * {@code difference} são nulos quando o cartão não está vinculado ou o banco não informou o limite.
 * {@code bankUsed = bankLimit - bankAvailable}; {@code difference = bankUsed - appUsed}, o valor que
 * falta lançar no app. {@code openInvoiceId}: a fatura que recebe um lançamento de hoje, nula se
 * ainda não existe.
 */
public record CardReconciliationResponse(
        BigDecimal bankLimit,
        BigDecimal bankAvailable,
        BigDecimal bankUsed,
        BigDecimal appUsed,
        BigDecimal difference,
        UUID openInvoiceId
) {}
