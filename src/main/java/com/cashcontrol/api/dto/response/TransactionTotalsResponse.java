package com.cashcontrol.api.dto.response;

import java.math.BigDecimal;

/**
 * Totais de todas as transações que casam com um filtro da listagem, não só da página.
 * Canceladas nunca entram. Transferências e ajustes ficam de fora. O pagamento de fatura tem
 * total próprio e entra no resultado só quando as compras do cartão estão fora do filtro: com
 * elas nas despesas, contaria duas vezes.
 */
public record TransactionTotalsResponse(
        BigDecimal incomeAmount,
        long incomeCount,
        BigDecimal expenseAmount,
        long expenseCount,
        BigDecimal invoicePaymentAmount,
        long invoicePaymentCount,
        BigDecimal netAmount,
        boolean invoicePaymentInNet
) {}
