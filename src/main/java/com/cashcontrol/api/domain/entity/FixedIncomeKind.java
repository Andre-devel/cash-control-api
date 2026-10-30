package com.cashcontrol.api.domain.entity;

/**
 * Tipo do título. Define a isenção de IR para pessoa física e de onde sai o valor: a curva do
 * indexador (CDB, LCI, LCA, OTHER com indexador), o preço do Tesouro (TESOURO_DIRETO) ou o
 * valor informado (OTHER sem indexador).
 */
public enum FixedIncomeKind {
    CDB(false),
    LCI(true),
    LCA(true),
    TESOURO_DIRETO(false),
    /** CRI, CRA, debênture etc. A isenção vem do pedido, porque varia de título para título. */
    OTHER(false);

    private final boolean taxExempt;

    FixedIncomeKind(boolean taxExempt) {
        this.taxExempt = taxExempt;
    }

    public boolean isTaxExempt() {
        return taxExempt;
    }
}
