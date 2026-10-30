package com.cashcontrol.api.service.openfinance;

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.entity.BankConnectionAccount;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.repository.BankConnectionAccountRepository;
import com.cashcontrol.api.repository.BankConnectionRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.openfinance.PluggyClient.PluggyTransaction;
import com.cashcontrol.api.service.statement.ParsedStatement;
import com.cashcontrol.api.service.statement.ParsedStatementRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Extrato da conta corrente buscado na Pluggy, como linhas da prévia do import.
 *
 * <p>Regras que evitam duplicar o que já está no app:
 * <ul>
 *   <li>a chave de import é {@code plg:} + id da transação na Pluggy, então buscar de novo não
 *       repete o que já foi gravado por este caminho — e essas linhas nem voltam para a prévia;</li>
 *   <li>o que veio por arquivo tem outra chave (hash da linha). Por isso só entra o que é do dia
 *       do último lançamento importado por arquivo em diante; o próprio dia entra porque o CSV
 *       pode ter sido baixado no meio dele, e a prévia marca a possível duplicata;</li>
 *   <li>lançamento pendente fica de fora: o valor ainda pode mudar.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PluggyStatementSource {

    public static final String REF_PREFIX = "plg:";

    /** O Inter data os lançamentos no horário de Brasília; a Pluggy devolve em UTC. */
    private static final ZoneId BANK_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final String HISTORY_SEPARATOR = " - ";

    private final PluggyClient pluggyClient;
    private final AppProperties appProperties;
    private final BankConnectionAccountRepository connectionAccountRepository;
    private final BankConnectionRepository connectionRepository;
    private final TransactionRepository transactionRepository;

    public BankStatement fetch(UUID accountId, UUID userId, LocalDate today) {
        if (!appProperties.getPluggy().isEnabled()) {
            throw new BusinessRuleException("A conexão com bancos não está configurada neste servidor.");
        }
        BankConnectionAccount link = connectionAccountRepository.findByAccountId(accountId)
                .filter(found -> connectionRepository.findByIdAndUserId(found.getConnectionId(), userId).isPresent())
                .orElseThrow(() -> new BusinessRuleException(
                        "Esta conta não está vinculada a uma conta do banco. Vincule em Perfil › Conexões bancárias."));
        LocalDate cutoff = transactionRepository.findLastFileImportDate(userId, accountId).orElse(null);

        List<PluggyTransaction> fetched = pluggyClient.listTransactions(link.getExternalAccountId()).stream()
                .filter(tx -> "POSTED".equals(tx.status()) && tx.id() != null && tx.date() != null && tx.amount() != null)
                .filter(tx -> cutoff == null || !dateOf(tx).isBefore(cutoff))
                .toList();
        Set<String> alreadyImported = fetched.isEmpty() ? Set.of() : new HashSet<>(transactionRepository
                .findExistingExternalRefs(userId, accountId, fetched.stream().map(PluggyStatementSource::refOf).toList()));

        List<ParsedStatementRow> rows = new ArrayList<>();
        List<String> refs = new ArrayList<>();
        for (PluggyTransaction tx : fetched) {
            String ref = refOf(tx);
            if (alreadyImported.contains(ref)) {
                continue;
            }
            rows.add(toRow(rows.size() + 1, tx));
            refs.add(ref);
        }
        String label = link.getNumber() != null ? link.getName() + " · " + link.getNumber() : link.getName();
        return new BankStatement(new ParsedStatement(label, cutoff, today, rows, List.of()), refs);
    }

    /**
     * A Pluggy junta as duas colunas do CSV do Inter em "Histórico - Descrição"; separadas e
     * com os espaços colapsados, a linha fica igual à do arquivo e casa com apelidos e regras.
     */
    static ParsedStatementRow toRow(int lineNumber, PluggyTransaction tx) {
        String text = tx.description() == null ? "" : tx.description();
        int separator = text.indexOf(HISTORY_SEPARATOR);
        String history = collapse(separator >= 0 ? text.substring(0, separator) : "");
        String description = collapse(separator >= 0 ? text.substring(separator + HISTORY_SEPARATOR.length()) : text);
        if (description.isEmpty()) {
            description = history;
        }
        BigDecimal absolute = tx.amount().abs();
        BigDecimal signed = "DEBIT".equals(tx.type()) ? absolute.negate() : absolute;
        return new ParsedStatementRow(lineNumber, dateOf(tx), history, description, signed);
    }

    private static LocalDate dateOf(PluggyTransaction tx) {
        return LocalDate.ofInstant(tx.date(), BANK_ZONE);
    }

    private static String refOf(PluggyTransaction tx) {
        return REF_PREFIX + tx.id();
    }

    private static String collapse(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }
}
