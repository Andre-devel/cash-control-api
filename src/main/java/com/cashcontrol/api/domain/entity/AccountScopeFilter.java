package com.cashcontrol.api.domain.entity;

/**
 * @author Andre
 * 04 de Novembro. de 2026
 */

/**
 * Filtro {@code scope} das listagens e agregados: um escopo só ou {@link #ALL}, que é o padrão e
 * devolve o mesmo de antes do escopo existir.
 */
public enum AccountScopeFilter {
    PERSONAL,
    BUSINESS,
    ALL;

    /** O escopo a filtrar, ou {@code null} para todos. */
    public AccountScope toScope() {
        return switch (this) {
            case PERSONAL -> AccountScope.PERSONAL;
            case BUSINESS -> AccountScope.BUSINESS;
            case ALL -> null;
        };
    }
}
