package com.cashcontrol.api.domain.entity;

/** O que a reconciliação de importações propõe corrigir num lançamento antigo. */
public enum ReconciliationKind {
    /** Despesa importada do extrato que é, na verdade, o pagamento de uma fatura do sistema. */
    INVOICE_PAYMENT,
    /** Despesa ou receita importada que é aplicação ou resgate de investimento. */
    INVESTMENT,
    /** Uma compra parcelada que ficou partida em mais de uma série. */
    SERIES_MERGE
}
