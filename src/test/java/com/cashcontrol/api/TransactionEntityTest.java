package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.Tag;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.TagRepository;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class TransactionEntityTest {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID testUserId;
    private Account testAccount;

    @BeforeEach
    void setUp() {
        testUserId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) " +
                "VALUES (?, " +
                "  (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), " +
                "  (SELECT id FROM auth_origins WHERE slug = 'LOCAL'), " +
                "  NOW()) " +
                "RETURNING id",
                UUID.class,
                "transaction-entity-test-" + UUID.randomUUID() + "@example.com");

        testAccount = new Account();
        testAccount.setUserId(testUserId);
        testAccount.setName("Test Account");
        testAccount.setType(AccountType.CHECKING);
        testAccount = accountRepository.save(testAccount);
    }

    @Test
    void canSaveAndRetrieveTransaction() {
        Transaction tx = buildTransaction(TransactionType.INCOME, TransactionStatus.PAID, "100.00");

        Transaction saved = transactionRepository.saveAndFlush(tx);

        assertThat(saved.getId()).isNotNull();

        Optional<Transaction> found = transactionRepository.findByIdAndUserId(saved.getId(), testUserId);
        assertThat(found).isPresent();
        assertThat(found.get().getDescription()).isEqualTo("Test Transaction");
        assertThat(found.get().getAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(found.get().getType()).isEqualTo(TransactionType.INCOME);
        assertThat(found.get().getStatus()).isEqualTo(TransactionStatus.PAID);
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    void allTransactionTypesAreStoredCorrectly() {
        for (TransactionType type : TransactionType.values()) {
            Transaction tx = buildTransaction(type, TransactionStatus.PAID, "50.00");
            Transaction saved = transactionRepository.save(tx);
            transactionRepository.flush();

            Optional<Transaction> found = transactionRepository.findById(saved.getId());
            assertThat(found.get().getType()).isEqualTo(type);
        }
    }

    @Test
    void allTransactionStatusesAreStoredCorrectly() {
        for (TransactionStatus status : TransactionStatus.values()) {
            Transaction tx = buildTransaction(TransactionType.EXPENSE, status, "25.00");
            Transaction saved = transactionRepository.save(tx);
            transactionRepository.flush();

            Optional<Transaction> found = transactionRepository.findById(saved.getId());
            assertThat(found.get().getStatus()).isEqualTo(status);
        }
    }

    @Test
    void canAssignTagsToTransaction() {
        Tag tag1 = new Tag();
        tag1.setUserId(testUserId);
        tag1.setName("food");
        tagRepository.save(tag1);

        Tag tag2 = new Tag();
        tag2.setUserId(testUserId);
        tag2.setName("subscription");
        tagRepository.save(tag2);

        Transaction tx = buildTransaction(TransactionType.EXPENSE, TransactionStatus.PAID, "50.00");
        tx.getTags().add(tag1);
        tx.getTags().add(tag2);
        transactionRepository.save(tx);
        transactionRepository.flush();

        transactionRepository.findByIdAndUserId(tx.getId(), testUserId).ifPresent(found -> {
            assertThat(found.getTags()).hasSize(2);
        });
    }

    @Test
    void amountUsesCorrectPrecision() {
        Transaction tx = buildTransaction(TransactionType.EXPENSE, TransactionStatus.PENDING, "1234567890.99");
        Transaction saved = transactionRepository.save(tx);
        transactionRepository.flush();

        Optional<Transaction> found = transactionRepository.findById(saved.getId());
        assertThat(found.get().getAmount()).isEqualByComparingTo(new BigDecimal("1234567890.99"));
    }

    @Test
    void findAllByUserId_returnsPaginatedResults() {
        for (int i = 0; i < 5; i++) {
            transactionRepository.save(buildTransaction(TransactionType.INCOME, TransactionStatus.PAID, "10.00"));
        }

        var page = transactionRepository.findAllByUserId(testUserId, org.springframework.data.domain.PageRequest.of(0, 3));
        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getTotalElements()).isEqualTo(5);
    }

    @Test
    void cancelledTransactionHasCancelledAtSet() {
        Transaction tx = buildTransaction(TransactionType.EXPENSE, TransactionStatus.CANCELLED, "75.00");
        tx.setCancelledAt(java.time.Instant.now());
        Transaction saved = transactionRepository.save(tx);

        Optional<Transaction> found = transactionRepository.findById(saved.getId());
        assertThat(found.get().getStatus()).isEqualTo(TransactionStatus.CANCELLED);
        assertThat(found.get().getCancelledAt()).isNotNull();
    }

    @Test
    void transferGroupIdLinksTransferLegs() {
        UUID groupId = UUID.randomUUID();

        Transaction debit = buildTransaction(TransactionType.TRANSFER, TransactionStatus.PAID, "200.00");
        debit.setTransferGroupId(groupId);
        transactionRepository.save(debit);

        Transaction credit = buildTransaction(TransactionType.TRANSFER, TransactionStatus.PAID, "200.00");
        credit.setTransferGroupId(groupId);
        transactionRepository.save(credit);

        List<Transaction> legs = transactionRepository.findAllByTransferGroupId(groupId);
        assertThat(legs).hasSize(2);
        assertThat(legs).allMatch(t -> groupId.equals(t.getTransferGroupId()));
    }

    private Transaction buildTransaction(TransactionType type, TransactionStatus status, String amount) {
        Transaction tx = new Transaction();
        tx.setUserId(testUserId);
        tx.setAccount(testAccount);
        tx.setType(type);
        tx.setStatus(status);
        tx.setAmount(new BigDecimal(amount));
        tx.setDescription("Test Transaction");
        tx.setCompetenceDate(LocalDate.now());
        if (status == TransactionStatus.PAID) {
            tx.setPaymentDate(LocalDate.now());
        }
        return tx;
    }
}
