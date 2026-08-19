package com.cashcontrol.api;

import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.domain.entity.CategoryRule;
import com.cashcontrol.api.dto.response.SuggestionSource;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.CategoryRuleMatcher;
import com.cashcontrol.api.service.CategorySuggester;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A cadeia RULE → HISTORY → NONE isolada do resto do import, para não precisar de um PDF ou
 * CSV inteiro só para checar a ordem de prioridade.
 */
@ExtendWith(MockitoExtension.class)
class CategorySuggesterTest {

    @Mock private TransactionRepository transactionRepository;

    private CategorySuggester suggester;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        suggester = new CategorySuggester(transactionRepository, new CategoryRuleMatcher());
    }

    @Test
    void suggest_prefersTheRuleOverTheHistory_evenWhenTheHistoryIsMoreNumerous() {
        Category food = category("Alimentação");
        Category market = category("Mercado");
        CategorySuggester.History history = new CategorySuggester.History(Map.of(
                "loja de teste", new CategorySuggester.Suggestion(
                        market.getId(), market.getName(), null, null, SuggestionSource.HISTORY)),
                Map.of());

        CategorySuggester.Suggestion suggestion = suggester.suggest(
                "LOJA DE TESTE", List.of(rule("loja de teste", food)), history);

        assertThat(suggestion.source()).isEqualTo(SuggestionSource.RULE);
        assertThat(suggestion.categoryId()).isEqualTo(food.getId());
    }

    @Test
    void suggest_fallsBackToTheHistoryWhenNoRuleMatches() {
        Category market = category("Mercado");
        Category marketSub = category("Hortifruti");
        CategorySuggester.History history = new CategorySuggester.History(Map.of(
                "mercado novo", new CategorySuggester.Suggestion(
                        market.getId(), market.getName(), marketSub.getId(), marketSub.getName(),
                        SuggestionSource.HISTORY)),
                Map.of());

        CategorySuggester.Suggestion suggestion = suggester.suggest("MERCADO NOVO", List.of(), history);

        assertThat(suggestion.source()).isEqualTo(SuggestionSource.HISTORY);
        assertThat(suggestion.categoryId()).isEqualTo(market.getId());
        assertThat(suggestion.subcategoryId()).isEqualTo(marketSub.getId());
    }

    @Test
    void suggest_isNoneWhenNeitherARuleNorTheHistoryMatch() {
        CategorySuggester.Suggestion suggestion = suggester.suggest("ALGO NUNCA VISTO", List.of(), new CategorySuggester.History(Map.of(), Map.of()));

        assertThat(suggestion.source()).isEqualTo(SuggestionSource.NONE);
        assertThat(suggestion.categoryId()).isNull();
    }

    @Test
    void suggest_doesNotFallBackToFrequencyGlobally_unlikeThePickersSuggestCategory() {
        // Sem entrada em history para essa chave: nem regra, nem histórico do
        // estabelecimento. NONE é o esperado, não a categoria mais frequente do usuário.
        CategorySuggester.Suggestion suggestion = suggester.suggest("ESTABELECIMENTO NOVO", List.of(), new CategorySuggester.History(Map.of(), Map.of()));

        assertThat(suggestion.source()).isEqualTo(SuggestionSource.NONE);
    }

    @Test
    void loadHistory_ignoresDescriptionsThatYieldNoMerchantKey() {
        CategorySuggester.History history = suggester.loadHistory(userId, List.of("123", "***"));

        assertThat(history.byMerchantKey()).isEmpty();
        assertThat(history.byToken()).isEmpty();
    }

    @Test
    void loadHistory_picksTheCategoryWithTheHighestCountPerMerchantKey() {
        Category food = category("Alimentação");
        Category market = category("Mercado");
        // A mesma chave, duas categorias concorrendo: "food" venceu duas vezes contra uma.
        when(transactionRepository.findCategoryHistoryByMerchantKeysOrTokenPattern(any(), any(), any())).thenReturn(List.of(
                new Object[]{"padaria sao joao", market.getId(), market.getName(), null, null, 1L},
                new Object[]{"padaria sao joao", food.getId(), food.getName(), null, null, 2L}));

        CategorySuggester.History history = suggester.loadHistory(userId, List.of("Padaria Sao Joao"));

        assertThat(history.byMerchantKey().get("padaria sao joao").categoryId()).isEqualTo(food.getId());
        assertThat(history.byMerchantKey().get("padaria sao joao").source()).isEqualTo(SuggestionSource.HISTORY);
    }

    @Test
    void suggest_fallsBackToATokenSharedWithAHistoryKeyWhenNoExactKeyMatches() {
        Category ai = category("Assinaturas");
        // Histórico só tem "claude ai subscription"; a linha nova é "anthropic* claude sub" —
        // chaves diferentes, mas "claude" é uma palavra em comum entre as duas.
        when(transactionRepository.findCategoryHistoryByMerchantKeysOrTokenPattern(any(), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{
                        "claude ai subscription", ai.getId(), ai.getName(), null, null, 1L}));

        CategorySuggester.History history = suggester.loadHistory(userId, List.of("ANTHROPIC* CLAUDE SUB"));
        CategorySuggester.Suggestion suggestion =
                suggester.suggest("ANTHROPIC* CLAUDE SUB", List.of(), history);

        assertThat(suggestion.source()).isEqualTo(SuggestionSource.HISTORY);
        assertThat(suggestion.categoryId()).isEqualTo(ai.getId());
    }

    @Test
    void suggest_doesNotTokenMatchOnWordsShorterThanTheMinimumLength() {
        Category market = category("Mercado");
        // "sub" tem 3 letras, abaixo do piso de token — não pode virar a identidade sozinho.
        when(transactionRepository.findCategoryHistoryByMerchantKeysOrTokenPattern(any(), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{"algo sub", market.getId(), market.getName(), null, null, 1L}));

        CategorySuggester.History history = suggester.loadHistory(userId, List.of("outra coisa sub"));
        CategorySuggester.Suggestion suggestion = suggester.suggest("outra coisa sub", List.of(), history);

        assertThat(suggestion.source()).isEqualTo(SuggestionSource.NONE);
    }

    private Category category(String name) {
        Category category = new Category();
        ReflectionTestUtils.setField(category, "id", UUID.randomUUID());
        category.setName(name);
        return category;
    }

    private CategoryRule rule(String pattern, Category category) {
        CategoryRule rule = new CategoryRule();
        rule.setPattern(pattern);
        rule.setCategory(category);
        return rule;
    }
}
