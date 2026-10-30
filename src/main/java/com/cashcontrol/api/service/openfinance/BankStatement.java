package com.cashcontrol.api.service.openfinance;

import com.cashcontrol.api.service.statement.ParsedStatement;

import java.util.List;

/**
 * Lançamentos buscados no banco, no formato que a prévia do import já lê.
 *
 * @param externalRefs chave de import de cada linha, na mesma ordem ({@code plg:} + id na Pluggy)
 */
public record BankStatement(ParsedStatement statement, List<String> externalRefs) {
}
