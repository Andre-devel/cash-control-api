package com.cashcontrol.api.dto.response;

import java.math.BigDecimal;

/**
 * Totais de todas as transações que casam com um filtro da listagem, não só da página.
 * Canceladas nunca entram. Transferências e ajustes ficam de fora; pagamento de fatura tem
 * total próprio porque as compras do cartão já contam nas despesas.
 */
public record TransactionTotalsResponse(
        BigDecimal incomeAmount,
        long incomeCount,
        BigDecimal expenseAmount,
        long expenseCount,
        BigDecimal invoicePaymentAmount,
        long invoicePaymentCount,
        BigDecimal netAmount
) {}
