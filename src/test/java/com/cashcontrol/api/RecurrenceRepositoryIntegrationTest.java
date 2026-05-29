package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.InstallmentSeries;
import com.cashcontrol.api.domain.entity.RecurrenceFrequency;
import com.cashcontrol.api.domain.entity.RecurrenceRule;
import com.cashcontrol.api.domain.entity.RecurrenceStatus;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.InstallmentSeriesRepository;
import com.cashcontrol.api.repository.RecurrenceRuleRepository;
import com.cashcontrol.api.repository.TransactionRepository;
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
class RecurrenceRepositoryIntegrationTest {

    @Autowired private RecurrenceRuleRepository recurrenceRuleRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private InstallmentSeriesRepository installmentSeriesRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private Account defaultAccount;

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
                "recurrencerepo-" + UUID.randomUUID() + "@example.com");

        defaultAccount = new Account();
        defaultAccount.setUserId(userId);
        defaultAccount.setName("Default");
        defaultAccount.setType(AccountType.CHECKING);
        defaultAccount.setCurrencyCode("BRL");
        defaultAccount = accountRepository.save(defaultAccount);
    }

    @Test
    void findAllByStatusAndNextOccurrenceDateLessThanEqual_returnsOnlyActiveDueRules() {
        LocalDate today = LocalDate.now();

        createRecurrenceRule(RecurrenceStatus.ACTIVE, today, "Due today");
        createRecurrenceRule(RecurrenceStatus.ACTIVE, today.plusDays(1), "Due tomorrow");
        createRecurrenceRule(RecurrenceStatus.PAUSED, today, "Paused rule");

        List<RecurrenceRule> due = recurrenceRuleRepository
                .findAllByStatusAndNextOccurrenceDateLessThanEqual(RecurrenceStatus.ACTIVE, today);

        assertThat(due).hasSize(1);
        assertThat(due.get(0).getDescription()).isEqualTo("Due today");
    }

    @Test
    void findAllByRecurrenceRule_IdAndStatusIn_filtersTransactionsByStatus() {
        RecurrenceRule rule = createRecurrenceRule(RecurrenceStatus.ACTIVE, LocalDate.now(), "Monthly rule");

        Transaction pending = saveTransaction(userId, defaultAccount, TransactionStatus.PENDING, rule, null);
        Transaction paid = saveTransaction(userId, defaultAccount, TransactionStatus.PAID, rule, null);

        List<Transaction> pendingOnly = transactionRepository.findAllByRecurrenceRule_IdAndStatusIn(
                rule.getId(), List.of(TransactionStatus.PENDING, TransactionStatus.OVERDUE));

        assertThat(pendingOnly).hasSize(1);
        assertThat(pendingOnly.get(0).getId()).isEqualTo(pending.getId());
    }

    @Test
    void findAllByInstallmentSeries_Id_returnsOnlyLinkedTransactions() {
        InstallmentSeries series = createInstallmentSeries();

        saveTransaction(userId, defaultAccount, TransactionStatus.PENDING, null, series);
        saveTransaction(userId, defaultAccount, TransactionStatus.PENDING, null, series);
        saveTransaction(userId, defaultAccount, TransactionStatus.PENDING, null, series);
        saveTransaction(userId, defaultAccount, TransactionStatus.PENDING, null, null);

        List<Transaction> linked = transactionRepository.findAllByInstallmentSeries_Id(series.getId());

        assertThat(linked).hasSize(3);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private RecurrenceRule createRecurrenceRule(RecurrenceStatus status, LocalDate nextOccurrence,
                                                 String description) {
        RecurrenceRule rule = new RecurrenceRule();
        rule.setUserId(userId);
        rule.setAccount(defaultAccount);
        rule.setType(TransactionType.EXPENSE);
        rule.setFrequency(RecurrenceFrequency.MONTHLY);
        rule.setStatus(status);
        rule.setAmount(new BigDecimal("100.00"));
        rule.setDescription(description);
        rule.setStartDate(LocalDate.now().minusMonths(1));
        rule.setNextOccurrenceDate(nextOccurrence);
        return recurrenceRuleRepository.save(rule);
    }

    private InstallmentSeries createInstallmentSeries() {
        InstallmentSeries series = new InstallmentSeries();
        series.setUserId(userId);
        series.setAccount(defaultAccount);
        series.setType(TransactionType.EXPENSE);
        series.setDescription("Test installment series");
        series.setTotalAmount(new BigDecimal("1200.00"));
        series.setTotalInstallments(3);
        series.setFirstPaymentDate(LocalDate.now());
        return installmentSeriesRepository.save(series);
    }

    private Transaction saveTransaction(UUID ownerId, Account account, TransactionStatus status,
                                         RecurrenceRule rule, InstallmentSeries series) {
        Transaction tx = new Transaction();
        tx.setUserId(ownerId);
        tx.setAccount(account);
        tx.setType(TransactionType.EXPENSE);
        tx.setStatus(status);
        tx.setAmount(new BigDecimal("100.00"));
        tx.setDescription("Test transaction");
        tx.setCompetenceDate(LocalDate.now());
        if (status == TransactionStatus.PAID) {
            tx.setPaymentDate(LocalDate.now());
        } else {
            tx.setPaymentDate(LocalDate.now().plusDays(5));
        }
        if (rule != null) {
            tx.setRecurrenceRule(rule);
        }
        if (series != null) {
            tx.setInstallmentSeries(series);
        }
        return transactionRepository.save(tx);
    }
}
