package com.cashcontrol.api.domain.entity;

/** Como a taxa da aplicação se aplica ao índice. */
public enum FixedIncomeIndexer {
    /** Percentual do CDI: {@code rate} = 110 para 110% do CDI. */
    CDI_PERCENT,
    /** CDI mais uma taxa: {@code rate} em % ao ano. */
    CDI_SPREAD,
    /** Prefixado: {@code rate} em % ao ano. */
    PREFIXED,
    /** IPCA mais uma taxa: {@code rate} em % ao ano. */
    IPCA_SPREAD,
    /** Percentual da Selic: {@code rate} = 100 para 100% da Selic. */
    SELIC_PERCENT
}
