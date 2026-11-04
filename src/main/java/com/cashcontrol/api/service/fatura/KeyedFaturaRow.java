package com.cashcontrol.api.service.fatura;

/**
 * @author Andre
 * 4 de Novembro. de 2026
 */

import com.cashcontrol.api.service.fatura.FaturaRowHasher.RowKey;

/**
 * Linha da fatura que já tem chave, calculada quando a fatura inteira foi lida. Quem recebe só
 * algumas linhas (a fila de revisão) não pode recontar o ordinal sobre elas.
 *
 * @param cardLast4 final da seção da fatura de onde a linha saiu
 */
public record KeyedFaturaRow(String cardLast4, RowKey key, ParsedFaturaRow row) {}
