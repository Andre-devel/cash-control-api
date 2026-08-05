package com.cashcontrol.api.service.statement;

import com.cashcontrol.api.domain.entity.StatementFormat;

import java.io.InputStream;

/**
 * Leitor de um formato de extrato bancário.
 *
 * <p>Implementações são {@code @Component} e o serviço de importação as resolve
 * pelo {@link #format()} — para suportar outro banco basta adicionar um valor no
 * enum e um bean aqui, sem tocar no serviço nem no controller.
 */
public interface StatementParser {

    StatementFormat format();

    /**
     * Lê o arquivo inteiro. Não fecha o {@code InputStream} — quem abriu fecha.
     *
     * @throws com.cashcontrol.api.domain.exception.BusinessRuleException quando o arquivo não tem a
     *                                                                    cara do formato (cabeçalho ausente, ilegível)
     */
    ParsedStatement parse(InputStream in);
}
