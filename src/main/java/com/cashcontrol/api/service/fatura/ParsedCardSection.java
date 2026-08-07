package com.cashcontrol.api.service.fatura;

import java.util.List;

/**
 * Bloco "CARTÃO ****XXXX" da fatura: os lançamentos de um cartão físico.
 *
 * <p>Uma fatura do Inter cobre o titular e os adicionais em um único PDF, e o
 * mesmo cartão pode reabrir a seção na página seguinte quando a tabela quebra —
 * o parser junta esses pedaços numa seção só, por {@code cardLast4}.
 *
 * @param cardLast4 os 4 últimos dígitos impressos no cabeçalho da seção
 */
public record ParsedCardSection(
        String cardLast4,
        List<ParsedFaturaRow> rows
) {}
