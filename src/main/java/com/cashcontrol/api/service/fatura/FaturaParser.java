package com.cashcontrol.api.service.fatura;

import com.cashcontrol.api.domain.entity.InvoiceImportFormat;

import java.io.InputStream;

/**
 * Leitor de um formato de fatura de cartão de crédito.
 *
 * <p>Implementações são {@code @Component} e o serviço de importação as resolve
 * pelo {@link #format()} — para suportar outro banco basta adicionar um valor no
 * enum e um bean aqui, sem tocar no serviço nem no controller.
 */
public interface FaturaParser {

    InvoiceImportFormat format();

    /**
     * Lê o arquivo inteiro. Não fecha o {@code InputStream} — quem abriu fecha.
     *
     * @throws com.cashcontrol.api.domain.exception.BusinessRuleException quando o arquivo não tem a
     *                                                                    cara do formato (seções ausentes, ilegível)
     */
    ParsedFatura parse(InputStream in);
}
