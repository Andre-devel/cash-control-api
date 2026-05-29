package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.domain.exception.ConflictException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateCategoryRequest;
import com.cashcontrol.api.dto.request.CreateTransactionRequest;
import com.cashcontrol.api.dto.response.CategoryResponse;
import com.cashcontrol.api.dto.response.CategorySuggestionResponse;
import com.cashcontrol.api.repository.CategoryRepository;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.CategoryService;
import com.cashcontrol.api.service.TransactionService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class CategoryServiceIntegrationTest {

    @Autowired private CategoryService categoryService;
    @Autowired private AccountService accountService;
    @Autowired private TransactionService transactionService;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @PersistenceContext private EntityManager entityManager;

    private UUID userId;
    private UUID userBId;
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
                "cat-service-a-" + UUID.randomUUID() + "@example.com");

        userBId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) " +
                "VALUES (?, " +
                "  (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), " +
                "  (SELECT id FROM auth_origins WHERE slug = 'LOCAL'), " +
                "  NOW()) " +
                "RETURNING id",
                UUID.class,
                "cat-service-b-" + UUID.randomUUID() + "@example.com");

        accountId = accountService.createAccount(
                new CreateAccountRequest("Test Account", AccountType.CHECKING, "BRL", null, 0, null),
                userId).id();
    }

    @Test
    void listCategories_systemAndUserCategoriesMerged() {
        List<CategoryResponse> systemOnly = categoryService.listCategories(userId, false, false);
        int systemCount = systemOnly.size();
        assertThat(systemCount).isGreaterThan(0);
        systemOnly.forEach(c -> assertThat(c.userId()).isNull());

        categoryService.createCategory(new CreateCategoryRequest("Pets", null, null, null, 0), userId);
        categoryService.createCategory(new CreateCategoryRequest("Viagens", null, null, null, 1), userId);

        List<CategoryResponse> all = categoryService.listCategories(userId, false, false);
        assertThat(all.size()).isEqualTo(systemCount + 2);

        long userCategoryCount = all.stream().filter(c -> userId.equals(c.userId())).count();
        assertThat(userCategoryCount).isEqualTo(2);
    }

    @Test
    void createCategory_nameUniquenessEnforcedPerUser() {
        categoryService.createCategory(new CreateCategoryRequest("Pets", null, null, null, 0), userId);

        assertThatThrownBy(() ->
                categoryService.createCategory(new CreateCategoryRequest("Pets", null, null, null, 0), userId))
                .isInstanceOf(ConflictException.class);

        CategoryResponse userBCategory = categoryService.createCategory(
                new CreateCategoryRequest("Pets", null, null, null, 0), userBId);
        assertThat(userBCategory.id()).isNotNull();
        assertThat(userBCategory.userId()).isEqualTo(userBId);
    }

    @Test
    void archiveCategory_cascadesToAllSubcategories() {
        CategoryResponse parent = categoryService.createCategory(
                new CreateCategoryRequest("Parent Category", null, null, null, 0), userId);

        categoryService.createCategory(
                new CreateCategoryRequest("Sub 1", parent.id(), null, null, 0), userId);
        categoryService.createCategory(
                new CreateCategoryRequest("Sub 2", parent.id(), null, null, 1), userId);
        categoryService.createCategory(
                new CreateCategoryRequest("Sub 3", parent.id(), null, null, 2), userId);

        categoryService.archiveCategory(parent.id(), userId);

        entityManager.flush();
        entityManager.clear();

        var reloadedParent = categoryRepository.findByIdAndUserId(parent.id(), userId).orElseThrow();
        assertThat(reloadedParent.isArchived()).isTrue();
        assertThat(reloadedParent.getArchivedAt()).isNotNull();

        var subcategories = categoryRepository.findSubcategoriesByUserIdAndParentId(userId, parent.id());
        assertThat(subcategories).hasSize(3);
        subcategories.forEach(sub -> {
            assertThat(sub.isArchived()).isTrue();
            assertThat(sub.getArchivedAt()).isNotNull();
        });
    }

    @Test
    void archiveCategory_systemCategoryRejected() {
        UUID systemCategoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM categories WHERE user_id IS NULL LIMIT 1", UUID.class);
        assertThat(systemCategoryId).isNotNull();

        // System categories have user_id = null; findByIdAndUserId cannot find them for a real userId
        assertThatThrownBy(() -> categoryService.archiveCategory(systemCategoryId, userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void suggestCategory_frequencyBasedResult() {
        CategoryResponse catA = categoryService.createCategory(
                new CreateCategoryRequest("Mercado Category", null, null, null, 0), userId);
        CategoryResponse catB = categoryService.createCategory(
                new CreateCategoryRequest("Other Category", null, null, null, 1), userId);

        for (int i = 0; i < 5; i++) {
            createPaidTransaction("Mercado compra " + i, catA.id());
        }
        createPaidTransaction("Mercado Extra", catB.id());

        List<CategorySuggestionResponse> suggestions = categoryService.suggestCategory("mercado", userId);

        assertThat(suggestions).isNotEmpty();
        assertThat(suggestions.get(0).categoryId()).isEqualTo(catA.id());
        assertThat(suggestions.get(0).matchCount()).isEqualTo(5L);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void createPaidTransaction(String description, UUID categoryId) {
        LocalDate today = LocalDate.now();
        transactionService.createTransaction(
                new CreateTransactionRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("100.00"), description,
                        today, today, null, categoryId, null, null, null, TransactionStatus.PAID),
                userId);
    }
}
