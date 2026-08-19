package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.domain.entity.InstallmentSeries;
import com.cashcontrol.api.domain.entity.PaymentMethod;
import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.response.SuggestionSource;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.CategoryRepository;
import com.cashcontrol.api.repository.InstallmentSeriesRepository;
import com.cashcontrol.api.repository.PaymentMethodRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.CategorySuggester;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A parte da sugestão por histórico que só um Postgres real garante: que
 * {@code COUNT(DISTINCT COALESCE(installmentSeries.id, id))} de fato conta uma série de
 * parcelas como uma decisão só, e não deixa uma compra em N vezes dominar o voto de outras
 * compras à vista do mesmo estabelecimento — a mesma armadilha do fallback por frequência
 * que o design documentado em {@code CategorySuggester} evita.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class CategorySuggesterIntegrationTest {

    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private InstallmentSeriesRepository installmentSeriesRepository;
    @Autowired private PaymentMethodRepository paymentMethodRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CategorySuggester categorySuggester;

    private UUID userId;
    private Account account;
    private PaymentMethod paymentMethod;

    private static final String MERCHANT_DESCRIPTION = "LOJA PARCELADA";

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
                "category-suggester-" + UUID.randomUUID() + "@example.com");

        account = new Account();
        account.setUserId(userId);
        account.setName("Conta de teste");
        account.setType(AccountType.CHECKING);
        account = accountRepository.save(account);

        paymentMethod = paymentMethodRepository.findBySlug(PaymentMethodSlug.OTHER).orElseThrow();
    }

    /**
     * Três parcelas da mesma compra, categoria A; duas compras à vista distintas do mesmo
     * estabelecimento, categoria B. Sem o {@code COUNT(DISTINCT ...)}, A venceria 3 a 2 — é
     * exatamente o contrário do que deveria acontecer, já que só houve uma decisão de
     * categorização para a série inteira.
     */
    @Test
    void findCategoryHistoryByMerchantKeysOrTokenPattern_countsAnInstallmentSeriesAsOneDecision() {
        Category categoryA = category("Categoria A");
        Category categoryB = category("Categoria B");

        InstallmentSeries series = installmentSeries(categoryA);
        saveTransaction(categoryA, series, 1);
        saveTransaction(categoryA, series, 2);
        saveTransaction(categoryA, series, 3);

        saveTransaction(categoryB, null, null);
        saveTransaction(categoryB, null, null);

        transactionRepository.flush();

        List<Object[]> rows = transactionRepository.findCategoryHistoryByMerchantKeysOrTokenPattern(
                userId, Set.of("loja parcelada"), "^$");

        Map<UUID, Long> countByCategory = rows.stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> (UUID) row[1], row -> (Long) row[5]));

        assertThat(countByCategory.get(categoryA.getId())).isEqualTo(1L);
        assertThat(countByCategory.get(categoryB.getId())).isEqualTo(2L);
    }

    /** A consequência prática: a categoria da compra à vista, repetida, é quem a sugestão elege. */
    @Test
    void loadHistory_prefersTheCategoryOfSeveralSeparatePurchasesOverAOneOffInstallmentSeries() {
        Category categoryA = category("Categoria A");
        Category categoryB = category("Categoria B");

        InstallmentSeries series = installmentSeries(categoryA);
        saveTransaction(categoryA, series, 1);
        saveTransaction(categoryA, series, 2);
        saveTransaction(categoryA, series, 3);

        saveTransaction(categoryB, null, null);
        saveTransaction(categoryB, null, null);

        transactionRepository.flush();

        CategorySuggester.History history =
                categorySuggester.loadHistory(userId, List.of(MERCHANT_DESCRIPTION));

        CategorySuggester.Suggestion suggestion = history.byMerchantKey().get("loja parcelada");
        assertThat(suggestion).isNotNull();
        assertThat(suggestion.categoryId()).isEqualTo(categoryB.getId());
        assertThat(suggestion.source()).isEqualTo(SuggestionSource.HISTORY);
    }

    private Category category(String name) {
        Category category = new Category();
        category.setUserId(userId);
        category.setName(name);
        return categoryRepository.save(category);
    }

    private InstallmentSeries installmentSeries(Category category) {
        InstallmentSeries series = new InstallmentSeries();
        series.setUserId(userId);
        series.setAccount(account);
        series.setPaymentMethod(paymentMethod);
        series.setType(TransactionType.EXPENSE);
        series.setDescription(MERCHANT_DESCRIPTION);
        series.setTotalAmount(new BigDecimal("150.00"));
        series.setTotalInstallments(3);
        series.setFirstPaymentDate(LocalDate.now());
        series.setCategory(category);
        return installmentSeriesRepository.save(series);
    }

    private void saveTransaction(Category category, InstallmentSeries series, Integer installmentNumber) {
        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setAccount(account);
        tx.setType(TransactionType.EXPENSE);
        tx.setStatus(TransactionStatus.PAID);
        tx.setAmount(new BigDecimal("50.00"));
        tx.setDescription(MERCHANT_DESCRIPTION);
        tx.setCompetenceDate(LocalDate.now());
        tx.setPaymentDate(LocalDate.now());
        tx.setPaymentMethod(paymentMethod);
        tx.setCategory(category);
        if (series != null) {
            tx.setInstallmentSeries(series);
            tx.setInstallmentNumber(installmentNumber);
            tx.setTotalInstallments(3);
        }
        transactionRepository.save(tx);
    }
}
