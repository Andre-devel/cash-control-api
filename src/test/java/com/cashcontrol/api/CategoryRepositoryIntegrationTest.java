package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.CategoryRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
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
class CategoryRepositoryIntegrationTest {

    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private Account defaultAccount;

    // Total system categories from V13 seed: 17 root + 16 subcategories = 33
    private static final int SYSTEM_CATEGORY_COUNT = 33;

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
                "catrepo-" + UUID.randomUUID() + "@example.com");

        defaultAccount = new Account();
        defaultAccount.setUserId(userId);
        defaultAccount.setName("Default");
        defaultAccount.setType(AccountType.CHECKING);
        defaultAccount.setCurrencyCode("BRL");
        defaultAccount = accountRepository.save(defaultAccount);
    }

    @Test
    void findAllSystemCategories_returnsOnlyNullUserIdRows() {
        List<Category> systemCategories = categoryRepository.findAllSystemCategories();

        assertThat(systemCategories).hasSize(SYSTEM_CATEGORY_COUNT);
        assertThat(systemCategories).allSatisfy(c ->
                assertThat(c.getUserId()).isNull());
    }

    @Test
    void findAllByUserId_returnsOnlyUserDefinedCategories() {
        createUserCategory(userId, "Pets", null);
        createUserCategory(userId, "Sports", null);

        List<Category> userCategories = categoryRepository.findAllByUserId(userId);

        assertThat(userCategories).hasSize(2);
        assertThat(userCategories).allSatisfy(c ->
                assertThat(c.getUserId()).isEqualTo(userId));
    }

    @Test
    void existsByUserIdAndParentIdAndName_scopedPerUserAndParent() {
        Category parent = createUserCategory(userId, "Moradia", null);
        createUserCategory(userId, "Aluguel", parent);

        assertThat(categoryRepository.existsByUserIdAndParentIdAndName(userId, parent.getId(), "Aluguel"))
                .isTrue();

        UUID otherUser = UUID.randomUUID();
        assertThat(categoryRepository.existsByUserIdAndParentIdAndName(otherUser, parent.getId(), "Aluguel"))
                .isFalse();

        Category otherParent = createUserCategory(userId, "Transporte", null);
        assertThat(categoryRepository.existsByUserIdAndParentIdAndName(userId, otherParent.getId(), "Aluguel"))
                .isFalse();
    }

    @Test
    void findTopCategoriesByFrequency_ranksHigherFrequencyFirst() {
        Category catA = createUserCategory(userId, "FreqCatA", null);
        Category catB = createUserCategory(userId, "FreqCatB", null);

        for (int i = 0; i < 3; i++) {
            saveTransactionWithCategory(userId, defaultAccount, catA, "Purchase " + i);
        }
        saveTransactionWithCategory(userId, defaultAccount, catB, "Single purchase");

        List<Object[]> top = transactionRepository.findTopCategoriesByFrequency(
                userId, PageRequest.of(0, 5));

        assertThat(top).isNotEmpty();
        UUID topId = (UUID) top.get(0)[0];
        assertThat(topId).isEqualTo(catA.getId());
    }

    @Test
    void findAllSystemCategories_noneHaveUserDefinedOwner() {
        createUserCategory(userId, "MyPersonalCategory", null);

        List<Category> systemCategories = categoryRepository.findAllSystemCategories();

        assertThat(systemCategories).noneMatch(c -> userId.equals(c.getUserId()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Category createUserCategory(UUID ownerId, String name, Category parent) {
        Category category = new Category();
        category.setUserId(ownerId);
        category.setName(name);
        category.setParent(parent);
        return categoryRepository.save(category);
    }

    private void saveTransactionWithCategory(UUID ownerId, Account account, Category category, String description) {
        Transaction tx = new Transaction();
        tx.setUserId(ownerId);
        tx.setAccount(account);
        tx.setType(TransactionType.EXPENSE);
        tx.setStatus(TransactionStatus.PAID);
        tx.setAmount(new BigDecimal("100.00"));
        tx.setDescription(description);
        tx.setCompetenceDate(LocalDate.now());
        tx.setPaymentDate(LocalDate.now());
        tx.setCategory(category);
        transactionRepository.save(tx);
    }
}
