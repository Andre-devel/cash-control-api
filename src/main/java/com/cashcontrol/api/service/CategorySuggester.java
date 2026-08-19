package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.CategoryRule;
import com.cashcontrol.api.dto.response.SuggestionSource;
import com.cashcontrol.api.repository.TransactionRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resolve a categoria sugerida para uma linha de importação, na ordem
 * RULE → HISTORY (chave exata) → HISTORY (token) → NONE.
 *
 * <p>RULE vem de {@link CategoryRuleMatcher}: intenção declarada pelo usuário, sempre
 * prioritária, inclusive sobre uma memória mais numerosa. HISTORY é a categoria mais usada
 * pelo próprio usuário para o estabelecimento daquela linha — ver {@link #loadHistory}.
 * Primeiro por {@code merchantKey} igual; sem isso, por uma palavra em comum com uma chave já
 * vista (o emissor do cartão manda a mesma assinatura com grafias diferentes de um mês para o
 * outro — {@code ANTHROPIC}, {@code CLAUDE.AI SUBSCRIPTION}, {@code ANTHROPIC* CLAUDE SUB} —
 * e {@link MerchantKey#of} só normaliza formatação, não sabe que são o mesmo comerciante).
 * Sem nenhuma das duas, NONE: propositalmente sem fallback por frequência global, que
 * pré-preencheria a fatura inteira com um palpite sem relação com a linha (ver
 * {@code CategoryServiceImpl.suggestCategory}, que é esse fallback e continua existindo só
 * para o picker de transação avulsa).
 */
@Component
public class CategorySuggester {

    private final TransactionRepository transactionRepository;
    private final CategoryRuleMatcher categoryRuleMatcher;

    public CategorySuggester(TransactionRepository transactionRepository, CategoryRuleMatcher categoryRuleMatcher) {
        this.transactionRepository = transactionRepository;
        this.categoryRuleMatcher = categoryRuleMatcher;
    }

    public record Suggestion(UUID categoryId, String categoryName, UUID subcategoryId, String subcategoryName,
                              SuggestionSource source) {

        static final Suggestion NONE = new Suggestion(null, null, null, null, SuggestionSource.NONE);
    }

    /**
     * Histórico do arquivo inteiro, indexado das duas formas que {@link #suggest} consulta:
     * por {@code merchantKey} exato e por token significativo.
     */
    public record History(Map<String, Suggestion> byMerchantKey, Map<String, Suggestion> byToken) {

        static final History EMPTY = new History(Map.of(), Map.of());
    }

    /**
     * A categoria mais frequente do histórico do usuário para cada {@code merchantKey}
     * encontrado nas descrições informadas, e para cada token significativo dessas chaves —
     * uma consulta para o arquivo inteiro, não uma por linha.
     *
     * <p>A contagem que decide o vencedor de cada chave/token vem de
     * {@link TransactionRepository#findCategoryHistoryByMerchantKeysOrTokenPattern}, que conta
     * uma série de parcelas como uma decisão só: sem isso, uma compra em 12x dominaria o voto
     * de doze compras à vista no mesmo estabelecimento.
     */
    public History loadHistory(UUID userId, Collection<String> descriptions) {
        Set<String> merchantKeys = descriptions.stream()
                .map(MerchantKey::of)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (merchantKeys.isEmpty()) {
            return History.EMPTY;
        }

        Set<String> tokens = merchantKeys.stream()
                .flatMap(key -> MerchantKey.significantTokens(key).stream())
                .collect(Collectors.toSet());
        String tokenPattern = tokens.isEmpty() ? "^$" : "\\y(" + String.join("|", tokens) + ")\\y";

        Map<String, Suggestion> byMerchantKey = new HashMap<>();
        Map<String, Long> byMerchantKeyCount = new HashMap<>();
        Map<String, Suggestion> byToken = new HashMap<>();
        Map<String, Long> byTokenCount = new HashMap<>();

        for (Object[] row : transactionRepository.findCategoryHistoryByMerchantKeysOrTokenPattern(
                userId, merchantKeys, tokenPattern)) {
            String merchantKey = (String) row[0];
            long count = (Long) row[5];
            Suggestion candidate = new Suggestion(
                    (UUID) row[1], (String) row[2], (UUID) row[3], (String) row[4], SuggestionSource.HISTORY);

            recordCandidate(byMerchantKey, byMerchantKeyCount, merchantKey, candidate, count);
            for (String token : MerchantKey.significantTokens(merchantKey)) {
                recordCandidate(byToken, byTokenCount, token, candidate, count);
            }
        }
        return new History(byMerchantKey, byToken);
    }

    /**
     * A sugestão para uma linha: a regra do usuário primeiro, depois a chave exata do
     * histórico já carregado por {@link #loadHistory}, depois — só na ausência das duas — o
     * token mais específico (mais longo) em comum com alguma chave já vista.
     */
    public Suggestion suggest(String description, List<CategoryRule> rules, History history) {
        Optional<CategoryRule> rule = categoryRuleMatcher.match(rules, description);
        if (rule.isPresent()) {
            CategoryRule matched = rule.get();
            return new Suggestion(
                    matched.getCategory().getId(), matched.getCategory().getName(),
                    matched.getSubcategory() != null ? matched.getSubcategory().getId() : null,
                    matched.getSubcategory() != null ? matched.getSubcategory().getName() : null,
                    SuggestionSource.RULE);
        }

        String merchantKey = MerchantKey.of(description);
        if (merchantKey == null) {
            return Suggestion.NONE;
        }

        Suggestion exact = history.byMerchantKey().get(merchantKey);
        if (exact != null) {
            return exact;
        }

        for (String token : MerchantKey.significantTokens(merchantKey)) {
            Suggestion fromToken = history.byToken().get(token);
            if (fromToken != null) {
                return fromToken;
            }
        }
        return Suggestion.NONE;
    }

    private static void recordCandidate(Map<String, Suggestion> best, Map<String, Long> bestCount,
                                         String key, Suggestion candidate, long count) {
        Long current = bestCount.get(key);
        if (current != null && current >= count) {
            return;
        }
        bestCount.put(key, count);
        best.put(key, candidate);
    }
}
