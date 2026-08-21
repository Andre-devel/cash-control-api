package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.InvoiceStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Uma linha da lista de faturas de um cartão — sem os itens, que só a tela de detalhe carrega.
 */
public record InvoiceSummaryResponse(
        UUID id,
        UUID creditCardId,
        String referenceMonth,
        InvoiceStatus status,
        LocalDate closingDate,
        LocalDate dueDate,
        BigDecimal totalAmount,
        BigDecimal paidAmount,
        boolean paidWithoutTransaction,
        long itemCount,
        long importedItemCount
) {}
