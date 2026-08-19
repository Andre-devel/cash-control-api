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
 * RULE → HISTORY → NONE.
 *
 * <p>RULE vem de {@link CategoryRuleMatcher}: intenção declarada pelo usuário, sempre
 * prioritária, inclusive sobre uma memória mais numerosa. HISTORY é a categoria mais usada
 * pelo próprio usuário para o {@code merchantKey} daquela linha — ver {@link #loadHistory}.
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
     * A categoria mais frequente do histórico do usuário para cada {@code merchantKey}
     * encontrado nas descrições informadas — uma consulta para o arquivo inteiro, não uma
     * por linha.
     *
     * <p>A contagem que decide o vencedor de cada chave vem de
     * {@link TransactionRepository#findCategoryHistoryByMerchantKeys}, que conta uma série de
     * parcelas como uma decisão só: sem isso, uma compra em 12x dominaria o voto de doze
     * compras à vista no mesmo estabelecimento.
     */
    public Map<String, Suggestion> loadHistory(UUID userId, Collection<String> descriptions) {
        Set<String> merchantKeys = descriptions.stream()
                .map(MerchantKey::of)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (merchantKeys.isEmpty()) {
            return Map.of();
        }

        Map<String, Suggestion> best = new HashMap<>();
        Map<String, Long> bestCount = new HashMap<>();
        for (Object[] row : transactionRepository.findCategoryHistoryByMerchantKeys(userId, merchantKeys)) {
            String merchantKey = (String) row[0];
            long count = (Long) row[5];
            Long current = bestCount.get(merchantKey);
            if (current != null && current >= count) {
                continue;
            }
            bestCount.put(merchantKey, count);
            best.put(merchantKey, new Suggestion(
                    (UUID) row[1], (String) row[2], (UUID) row[3], (String) row[4], SuggestionSource.HISTORY));
        }
        return best;
    }

    /**
     * A sugestão para uma linha: a regra do usuário primeiro, depois o histórico já
     * carregado por {@link #loadHistory} para o arquivo inteiro.
     */
    public Suggestion suggest(String description, List<CategoryRule> rules, Map<String, Suggestion> history) {
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
        Suggestion fromHistory = merchantKey != null ? history.get(merchantKey) : null;
        return fromHistory != null ? fromHistory : Suggestion.NONE;
    }
}
