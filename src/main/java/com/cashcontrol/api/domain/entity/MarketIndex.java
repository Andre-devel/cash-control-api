package com.cashcontrol.api.domain.entity;

/**
 * Índices sincronizados do SGS do Banco Central, com o código da série de cada um.
 *
 * <p>CDI e Selic são diários (% ao dia útil, uma linha por dia útil — a série do CDI serve
 * também de calendário de dias úteis). O IPCA é mensal (% no mês, datado no dia 1).
 */
public enum MarketIndex {
    CDI(12, true),
    SELIC(11, true),
    IPCA(433, false);

    private final int sgsSeries;
    private final boolean daily;

    MarketIndex(int sgsSeries, boolean daily) {
        this.sgsSeries = sgsSeries;
        this.daily = daily;
    }

    public int getSgsSeries() {
        return sgsSeries;
    }

    public boolean isDaily() {
        return daily;
    }
}
