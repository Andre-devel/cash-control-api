package com.cashcontrol.api.domain.entity;

public enum TransactionType {
    INCOME, EXPENSE, TRANSFER, REFUND, MANUAL_ADJUSTMENT,
    /**
     * Saída da conta que quita uma fatura de cartão. Gravado assinado (negativo), como a perna
     * de origem de uma transferência: move dinheiro, não é gasto — o gasto são as compras da
     * fatura, que passam a PAGAS quando ela é paga.
     */
    INVOICE_PAYMENT
}
