package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.repository.AccountRepository;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class AccountRepositoryIntegrationTest {

    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID userId;

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
                "accrepo-" + UUID.randomUUID() + "@example.com");
    }

    @Test
    void findAllByUserIdAndDeletedAtIsNull_excludesSoftDeleted() {
        createAccount(userId, "Active A", false);
        createAccount(userId, "Active B", false);
        Account deleted = createAccount(userId, "Deleted", false);
        deleted.setDeletedAt(Instant.now());
        accountRepository.save(deleted);

        List<Account> result = accountRepository.findAllByUserIdAndDeletedAtIsNull(userId);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(Account::getName)
                .containsExactlyInAnyOrder("Active A", "Active B");
    }

    @Test
    void findByIdAndUserIdAndDeletedAtIsNull_crossUserReturnsEmpty() {
        Account account = createAccount(userId, "My Account", false);
        UUID otherUser = UUID.randomUUID();

        Optional<Account> result = accountRepository.findByIdAndUserIdAndDeletedAtIsNull(account.getId(), otherUser);

        assertThat(result).isEmpty();
    }

    @Test
    void existsByUserIdAndNameAndDeletedAtIsNull_scopedPerUser() {
        createAccount(userId, "Nubank", false);

        assertThat(accountRepository.existsByUserIdAndNameAndDeletedAtIsNull(userId, "Nubank")).isTrue();

        UUID otherUser = UUID.randomUUID();
        assertThat(accountRepository.existsByUserIdAndNameAndDeletedAtIsNull(otherUser, "Nubank")).isFalse();
    }

    @Test
    void existsByAccount_IdAndUserIdAndStatusNotIn_detectsActiveTransactions() {
        Account account = createAccount(userId, "Account with transactions", false);

        saveTransaction(userId, account, TransactionStatus.PAID);
        saveTransaction(userId, account, TransactionStatus.CANCELLED);

        boolean hasPaidTransactions = transactionRepository.existsByAccount_IdAndUserIdAndStatusNotIn(
                account.getId(), userId, List.of(TransactionStatus.CANCELLED));

        assertThat(hasPaidTransactions).isTrue();
    }

    @Test
    void existsByAccount_IdAndUserIdAndStatusNotIn_onlyCancelledReturnsEmpty() {
        Account account = createAccount(userId, "Only cancelled account", false);

        saveTransaction(userId, account, TransactionStatus.CANCELLED);

        boolean hasNonCancelled = transactionRepository.existsByAccount_IdAndUserIdAndStatusNotIn(
                account.getId(), userId, List.of(TransactionStatus.CANCELLED));

        assertThat(hasNonCancelled).isFalse();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Account createAccount(UUID ownerId, String name, boolean deleted) {
        Account account = new Account();
        account.setUserId(ownerId);
        account.setName(name);
        account.setType(AccountType.CHECKING);
        account.setCurrencyCode("BRL");
        if (deleted) {
            account.setDeletedAt(Instant.now());
        }
        return accountRepository.save(account);
    }

    private void saveTransaction(UUID ownerId, Account account, TransactionStatus status) {
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
        }
        transactionRepository.save(tx);
    }
}
