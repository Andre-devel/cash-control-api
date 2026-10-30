package com.cashcontrol.api.service.investment;

/** De onde veio o valor bruto de uma aplicação. */
public enum ValuationSource {
    /** Curva do indexador sobre o principal (ou sobre o último valor informado). */
    CURVE,
    /** Quantidade × PU do Tesouro Direto (marcação a mercado). */
    MARKET,
    /** Valor informado pelo usuário, sem curva para continuar dele. */
    MANUAL,
    /** Sem curva, sem cotação e sem valor informado: vale o custo. */
    COST
}
