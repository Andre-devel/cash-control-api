package com.cashcontrol.api.domain.entity;

/** Como terminou uma leitura de item da Pluggy. Ver V44. */
public enum BankSyncOutcome {
    /** O banco tinha dados novos desde a leitura anterior, e tudo foi lido. */
    SUCCESS,
    /** Lido sem erro, mas o item não sincronizou com o banco desde a leitura anterior. */
    NO_CHANGE,
    /** Contas lidas, mas a fila de revisão ou os investimentos falharam em parte. */
    PARTIAL,
    /** A leitura falhou, ou a Pluggy devolveu o item com erro do banco. */
    ERROR,
    /** A Pluggy não conhece mais o item; a conexão foi apagada. */
    ITEM_GONE
}
