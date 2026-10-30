package com.cashcontrol.api.domain.entity;

/**
 * Origem da fatura de cartão importada: o formato do arquivo, ou o banco direto.
 *
 * <p>Enum e não lookup table pelo mesmo motivo de {@link StatementFormat}: o
 * conjunto é determinado pelo código — cada formato de arquivo novo aqui exige um
 * {@code FaturaParser} correspondente.
 */
public enum InvoiceImportFormat {

    /** Fatura de cartão de crédito do Banco Inter, em PDF. */
    INTER_FATURA_PDF,

    /**
     * Fatura fechada buscada no banco pela Pluggy, sem arquivo. Não tem parser: a prévia vem
     * de {@code FaturaImportService.previewFromBank}, e o valor só identifica a origem no commit.
     */
    OPEN_FINANCE
}
