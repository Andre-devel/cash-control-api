package com.cashcontrol.api.domain.entity;

/**
 * Formato do arquivo de extrato enviado para importação.
 *
 * <p>Enum e não lookup table: o conjunto é determinado pelos parsers que existem
 * no código, não por dado do usuário — cada valor novo aqui exige um
 * {@code StatementParser} correspondente.
 */
public enum StatementFormat {

    /** Extrato de conta corrente do Banco Inter, exportado em CSV. */
    INTER_CSV
}
