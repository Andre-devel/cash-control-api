package com.cashcontrol.api.domain.entity;

/**
 * Origem do extrato importado: o formato do arquivo, ou o banco direto.
 *
 * <p>Enum e não lookup table: o conjunto é determinado pelo código, não por dado do
 * usuário — cada formato de arquivo novo aqui exige um {@code StatementParser}
 * correspondente.
 */
public enum StatementFormat {

    /** Extrato de conta corrente do Banco Inter, exportado em CSV. */
    INTER_CSV,

    /**
     * Lançamentos buscados no banco pela Pluggy, sem arquivo. Não tem parser: a prévia vem
     * de {@code StatementImportService.previewFromBank}, e o valor só identifica a origem no
     * commit.
     */
    OPEN_FINANCE
}
