package com.cashcontrol.api.service.openfinance;

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.entity.BankConnectionAccount;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.dto.response.BankBillResponse;
import com.cashcontrol.api.repository.BankConnectionAccountRepository;
import com.cashcontrol.api.repository.BankConnectionRepository;
import com.cashcontrol.api.service.fatura.ParsedCardSection;
import com.cashcontrol.api.service.fatura.ParsedFatura;
import com.cashcontrol.api.service.fatura.ParsedFaturaRow;
import com.cashcontrol.api.service.openfinance.PluggyClient.CreditCardMetadata;
import com.cashcontrol.api.service.openfinance.PluggyClient.PluggyBill;
import com.cashcontrol.api.service.openfinance.PluggyClient.PluggyTransaction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fatura fechada do cartão buscada na Pluggy, no formato que a prévia do PDF já lê.
 *
 * <p>A chave de cada linha sai do mesmo {@code FaturaRowHasher} do PDF, e não de {@code plg:} +
 * id como no extrato: a parcela seguinte que a confirmação gera precisa nascer com a chave que a
 * linha do mês que vem vai produzir, e o id da Pluggy daquela linha ainda não existe. A
 * descrição e o valor da Pluggy não são os do PDF, então as chaves das duas origens nunca
 * coincidem; a prévia avisa a possível duplicata por outro caminho.
 *
 * <p>Só entra lançamento já fechado em fatura ({@code billId} preenchido): antes do fechamento
 * ele ainda pode mudar e não tem fatura certa.
 */
@Component
@RequiredArgsConstructor
public class PluggyFaturaSource {

    private static final ZoneId BANK_ZONE = ZoneId.of("America/Sao_Paulo");

    private final PluggyClient pluggyClient;
    private final AppProperties appProperties;
    private final BankConnectionAccountRepository connectionAccountRepository;
    private final BankConnectionRepository connectionRepository;

    /** Faturas fechadas do cartão do banco vinculado, da mais recente para a mais antiga. */
    public List<BankBillResponse> listBills(UUID creditCardId, UUID userId) {
        BankConnectionAccount link = requireLink(creditCardId, userId);
        return pluggyClient.listBills(link.getExternalAccountId()).stream()
                .filter(bill -> bill.id() != null && bill.dueDate() != null)
                .sorted(Comparator.comparing(PluggyBill::dueDate).reversed())
                .map(bill -> new BankBillResponse(bill.id(), dateOf(bill.dueDate()), bill.totalAmount()))
                .toList();
    }

    public ParsedFatura fetch(UUID creditCardId, String billId, UUID userId) {
        BankConnectionAccount link = requireLink(creditCardId, userId);
        PluggyBill bill = pluggyClient.listBills(link.getExternalAccountId()).stream()
                .filter(found -> billId.equals(found.id()) && found.dueDate() != null)
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("Fatura não encontrada no banco."));

        List<PluggyTransaction> rows = pluggyClient.listTransactions(link.getExternalAccountId()).stream()
                .filter(tx -> "POSTED".equals(tx.status()) && tx.date() != null && tx.amount() != null
                              && tx.creditCardMetadata() != null && billId.equals(tx.creditCardMetadata().billId()))
                // O PDF é cronológico, e a ordem decide o ordinal de linhas iguais; o id desempata
                // para que buscar de novo dê a mesma ordem.
                .sorted(Comparator.comparing((PluggyTransaction tx) -> dateOf(tx))
                        .thenComparing(tx -> tx.id() == null ? "" : tx.id()))
                .toList();

        String fallbackLast4 = link.getNumber();
        Map<String, List<ParsedFaturaRow>> sections = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            PluggyTransaction tx = rows.get(i);
            sections.computeIfAbsent(last4Of(tx.creditCardMetadata().cardNumber(), fallbackLast4),
                    key -> new ArrayList<>()).add(toRow(i + 1, tx));
        }
        // O titular (o final do vínculo) vem primeiro, como no PDF; os virtuais e adicionais depois.
        return new ParsedFatura(dateOf(bill.dueDate()), bill.totalAmount(),
                sections.entrySet().stream()
                        .sorted(Comparator.comparing(entry -> !entry.getKey().equals(fallbackLast4)))
                        .map(entry -> new ParsedCardSection(entry.getKey(), entry.getValue()))
                        .toList(),
                List.of());
    }

    /**
     * Na parcela vale a data da compra, como no PDF, que a repete em toda parcela: é ela que
     * entra na chave e precisa ser a mesma mês a mês.
     */
    static ParsedFaturaRow toRow(int lineNumber, PluggyTransaction tx) {
        CreditCardMetadata card = tx.creditCardMetadata();
        boolean parceled = card.installmentNumber() != null && card.totalInstallments() != null
                           && card.totalInstallments() > 1 && card.purchaseDate() != null;
        LocalDate date = parceled ? card.purchaseDate().atOffset(ZoneOffset.UTC).toLocalDate() : dateOf(tx);
        BigDecimal absolute = tx.amount().abs();
        String description = tx.description() == null ? "" : tx.description().replaceAll("\\s+", " ").trim();
        return new ParsedFaturaRow(lineNumber, date, description,
                "DEBIT".equals(tx.type()) ? absolute.negate() : absolute,
                parceled ? card.installmentNumber() : null,
                parceled ? card.totalInstallments() : null);
    }

    private BankConnectionAccount requireLink(UUID creditCardId, UUID userId) {
        if (!appProperties.getPluggy().isEnabled()) {
            throw new BusinessRuleException("A conexão com bancos não está configurada neste servidor.");
        }
        return connectionAccountRepository.findByCreditCardId(creditCardId)
                .filter(found -> connectionRepository.findByIdAndUserId(found.getConnectionId(), userId).isPresent())
                .orElseThrow(() -> new BusinessRuleException(
                        "Este cartão não está vinculado a um cartão do banco. Vincule em Perfil › Conexões bancárias."));
    }

    /** O Inter data os lançamentos à meia-noite de Brasília; a Pluggy devolve em UTC. */
    private static LocalDate dateOf(PluggyTransaction tx) {
        return LocalDate.ofInstant(tx.date(), BANK_ZONE);
    }

    /** Vencimento vem à meia-noite UTC: o dia é o da própria data. */
    private static LocalDate dateOf(Instant dueDate) {
        return dueDate.atOffset(ZoneOffset.UTC).toLocalDate();
    }

    private static String last4Of(String cardNumber, String fallback) {
        String digits = cardNumber == null ? "" : cardNumber.replaceAll("\\D", "");
        if (digits.length() < 4) {
            digits = fallback == null ? "" : fallback.replaceAll("\\D", "");
        }
        return digits.length() >= 4 ? digits.substring(digits.length() - 4) : "0000";
    }
}
