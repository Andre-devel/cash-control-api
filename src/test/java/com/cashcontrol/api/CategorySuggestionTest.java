package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateTransactionRequest;
import com.cashcontrol.api.dto.response.AccountResponse;
import com.cashcontrol.api.dto.response.CategorySuggestionResponse;
import com.cashcontrol.api.repository.CategoryRepository;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.CategoryService;
import com.cashcontrol.api.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class CategorySuggestionTest {

    @Autowired private CategoryService categoryService;
    @Autowired private AccountService accountService;
    @Autowired private TransactionService transactionService;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) " +
                "VALUES (?, " +
                "  (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), " +
                "  (SELECT id FROM auth_origins WHERE slug = 'LOCAL'), " +
                "  NOW()) " +
                "RETURNING id",
                UUID.class,
                "category-suggestion-" + UUID.randomUUID() + "@example.com");

        AccountResponse account = accountService.createAccount(
                new CreateAccountRequest("Test Account", AccountType.CHECKING, "BRL", null, 0, null),
                userId);
        accountId = account.id();
    }

    @Test
    void suggestCategory_matchesByDescriptionText() {
        UUID subscriptionsCatId = jdbcTemplate.queryForObject(
                "SELECT id FROM categories WHERE name = 'Assinaturas' AND user_id IS NULL AND parent_id IS NULL",
                UUID.class);

        transactionService.createTransaction(
                new CreateTransactionRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("49.90"), "Netflix monthly subscription",
                        LocalDate.now(), LocalDate.now(), null,
                        subscriptionsCatId, null, null, null, null, null, null),
                userId);

        transactionService.createTransaction(
                new CreateTransactionRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("39.90"), "Netflix annual plan",
                        LocalDate.now(), LocalDate.now(), null,
                        subscriptionsCatId, null, null, null, null, null, null),
                userId);

        List<CategorySuggestionResponse> suggestions = categoryService.suggestCategory("Netflix", userId);

        assertThat(suggestions).isNotEmpty();
        assertThat(suggestions.get(0).categoryId()).isEqualTo(subscriptionsCatId);
        assertThat(suggestions.get(0).categoryName()).isEqualTo("Assinaturas");
        assertThat(suggestions.get(0).matchCount()).isEqualTo(2L);
    }

    @Test
    void suggestCategory_fallsBackToFrequencyWhenNoMatch() {
        UUID foodCatId = jdbcTemplate.queryForObject(
                "SELECT id FROM categories WHERE name = 'Alimentação' AND user_id IS NULL AND parent_id IS NULL",
                UUID.class);

        for (int i = 1; i <= 5; i++) {
            transactionService.createTransaction(
                    new CreateTransactionRequest(accountId, TransactionType.EXPENSE,
                            new BigDecimal("25.00"), "Supermarket purchase " + i,
                            LocalDate.now(), LocalDate.now(), null,
                            foodCatId, null, null, null, null, null, null),
                    userId);
        }

        List<CategorySuggestionResponse> suggestions = categoryService.suggestCategory("unknown description xyz", userId);

        assertThat(suggestions).isNotEmpty();
        assertThat(suggestions.get(0).categoryId()).isEqualTo(foodCatId);
    }

    @Test
    void suggestCategory_withNullDescription_fallsBackToFrequency() {
        UUID healthCatId = jdbcTemplate.queryForObject(
                "SELECT id FROM categories WHERE name = 'Saúde' AND user_id IS NULL AND parent_id IS NULL",
                UUID.class);

        for (int i = 1; i <= 3; i++) {
            transactionService.createTransaction(
                    new CreateTransactionRequest(accountId, TransactionType.EXPENSE,
                            new BigDecimal("150.00"), "Doctor appointment " + i,
                            LocalDate.now(), LocalDate.now(), null,
                            healthCatId, null, null, null, null, null, null),
                    userId);
        }

        List<CategorySuggestionResponse> suggestions = categoryService.suggestCategory(null, userId);

        assertThat(suggestions).isNotEmpty();
        assertThat(suggestions.get(0).categoryId()).isEqualTo(healthCatId);
    }

    @Test
    void suggestCategory_returnsAtMostFiveSuggestions() {
        List<UUID> categoryIds = jdbcTemplate.queryForList(
                "SELECT id FROM categories WHERE user_id IS NULL AND parent_id IS NULL LIMIT 6",
                UUID.class);

        for (UUID catId : categoryIds) {
            transactionService.createTransaction(
                    new CreateTransactionRequest(accountId, TransactionType.EXPENSE,
                            new BigDecimal("10.00"), "Transaction for cat " + catId,
                            LocalDate.now(), LocalDate.now(), null,
                            catId, null, null, null, null, null, null),
                    userId);
        }

        List<CategorySuggestionResponse> suggestions = categoryService.suggestCategory(null, userId);

        assertThat(suggestions).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    void suggestCategory_noTransactionHistory_returnsEmptyList() {
        List<CategorySuggestionResponse> suggestions = categoryService.suggestCategory("anything", userId);

        assertThat(suggestions).isEmpty();
    }

    @Test
    void suggestCategory_ranksHigherFrequencyFirst() {
        UUID foodCatId = jdbcTemplate.queryForObject(
                "SELECT id FROM categories WHERE name = 'Alimentação' AND user_id IS NULL AND parent_id IS NULL",
                UUID.class);
        UUID transportCatId = jdbcTemplate.queryForObject(
                "SELECT id FROM categories WHERE name = 'Transporte' AND user_id IS NULL AND parent_id IS NULL",
                UUID.class);

        for (int i = 1; i <= 4; i++) {
            transactionService.createTransaction(
                    new CreateTransactionRequest(accountId, TransactionType.EXPENSE,
                            new BigDecimal("20.00"), "restaurant food " + i,
                            LocalDate.now(), LocalDate.now(), null,
                            foodCatId, null, null, null, null, null, null),
                    userId);
        }

        transactionService.createTransaction(
                new CreateTransactionRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("5.00"), "bus transport fare",
                        LocalDate.now(), LocalDate.now(), null,
                        transportCatId, null, null, null, null, null, null),
                userId);

        List<CategorySuggestionResponse> suggestions = categoryService.suggestCategory(null, userId);

        assertThat(suggestions).isNotEmpty();
        assertThat(suggestions.get(0).categoryId()).isEqualTo(foodCatId);
        assertThat(suggestions.get(0).matchCount()).isGreaterThan(1L);
    }

    @Test
    void suggestCategory_withSubcategoryHistory_returnsCategoryAndSubcategory() {
        UUID housingCatId = jdbcTemplate.queryForObject(
                "SELECT id FROM categories WHERE name = 'Moradia' AND user_id IS NULL AND parent_id IS NULL",
                UUID.class);
        UUID rentSubcatId = jdbcTemplate.queryForObject(
                "SELECT c.id FROM categories c " +
                "JOIN categories p ON c.parent_id = p.id " +
                "WHERE c.name = 'Aluguel' AND p.name = 'Moradia' AND c.user_id IS NULL",
                UUID.class);

        for (int i = 1; i <= 3; i++) {
            transactionService.createTransaction(
                    new CreateTransactionRequest(accountId, TransactionType.EXPENSE,
                            new BigDecimal("1200.00"), "Monthly rent payment " + i,
                            LocalDate.now(), LocalDate.now(), null,
                            housingCatId, rentSubcatId, null, null, null, null, null),
                    userId);
        }

        List<CategorySuggestionResponse> suggestions = categoryService.suggestCategory("rent", userId);

        assertThat(suggestions).isNotEmpty();
        CategorySuggestionResponse top = suggestions.get(0);
        assertThat(top.categoryId()).isEqualTo(housingCatId);
        assertThat(top.subcategoryId()).isEqualTo(rentSubcatId);
        assertThat(top.subcategoryName()).isEqualTo("Aluguel");
    }
}
