package com.cashcontrol.api.domain.entity;

/**
 * Formato do arquivo de fatura de cartão enviado para importação.
 *
 * <p>Enum e não lookup table pelo mesmo motivo de {@link StatementFormat}: o
 * conjunto é determinado pelos parsers que existem no código — cada valor novo
 * aqui exige um {@code FaturaParser} correspondente.
 */
public enum InvoiceImportFormat {

    /** Fatura de cartão de crédito do Banco Inter, em PDF. */
    INTER_FATURA_PDF
}
