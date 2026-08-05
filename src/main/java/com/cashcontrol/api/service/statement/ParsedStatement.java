package com.cashcontrol.api.service.statement;

import com.cashcontrol.api.dto.response.ImportRowError;

import java.time.LocalDate;
import java.util.List;

/**
 * Resultado da leitura de um arquivo de extrato.
 *
 * <p>Uma linha malformada não derruba o arquivo inteiro: ela vai para
 * {@code errors} e a leitura continua. O usuário vê o que não deu para ler e
 * ainda assim importa o resto.
 *
 * @param sourceAccountLabel identificação da conta no cabeçalho do arquivo, quando o formato traz
 *                           (o número da conta no extrato do Inter). Só serve para o usuário
 *                           conferir que está importando na conta certa.
 * @param periodStart        início do período declarado no cabeçalho, quando disponível
 * @param periodEnd          fim do período declarado no cabeçalho, quando disponível
 */
public record ParsedStatement(
        String sourceAccountLabel,
        LocalDate periodStart,
        LocalDate periodEnd,
        List<ParsedStatementRow> rows,
        List<ImportRowError> errors
) {}
