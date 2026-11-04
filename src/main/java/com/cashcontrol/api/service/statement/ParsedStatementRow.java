package com.cashcontrol.api.service.statement;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Uma linha de lançamento lida do arquivo, ainda crua: sem tipo, sem forma de
 * pagamento e sem categoria. A tradução para o domínio é do
 * {@link StatementHistoryMapper}.
 *
 * @param lineNumber   número da linha no arquivo (1-based), para reportar erros
 * @param date         data do lançamento
 * @param rawHistory   coluna "Histórico" como veio do banco
 * @param description  coluna "Descrição" como veio do banco
 * @param signedAmount valor com sinal — negativo é saída da conta
 * @param counterpartyName     quem recebeu (saída) ou pagou (entrada), quando o banco informa;
 *                             {@code null} no import de arquivo
 * @param counterpartyDocument CPF/CNPJ da contraparte, mascarado como o banco mandou
 */
public record ParsedStatementRow(
        int lineNumber,
        LocalDate date,
        String rawHistory,
        String description,
        BigDecimal signedAmount,
        String counterpartyName,
        String counterpartyDocument
) {

    /** Linha de arquivo: o extrato não traz contraparte. */
    public ParsedStatementRow(int lineNumber, LocalDate date, String rawHistory, String description,
                              BigDecimal signedAmount) {
        this(lineNumber, date, rawHistory, description, signedAmount, null, null);
    }
}
