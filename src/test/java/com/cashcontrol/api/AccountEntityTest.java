package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class AccountEntityTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID testUserId;

    @BeforeEach
    void createTestUser() {
        testUserId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) " +
                "VALUES (?, " +
                "  (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), " +
                "  (SELECT id FROM auth_origins WHERE slug = 'LOCAL'), " +
                "  NOW()) " +
                "RETURNING id",
                UUID.class,
                "account-entity-test-" + UUID.randomUUID() + "@example.com");
    }

    @Test
    void canSaveAndRetrieveAccount() {
        Account account = new Account();
        account.setUserId(testUserId);
        account.setName("My Checking Account");
        account.setType(AccountType.CHECKING);
        account.setCurrencyCode("BRL");
        account.setDescription("Primary account");

        Account saved = accountRepository.saveAndFlush(account);

        assertThat(saved.getId()).isNotNull();

        Optional<Account> found = accountRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("My Checking Account");
        assertThat(found.get().getType()).isEqualTo(AccountType.CHECKING);
        assertThat(found.get().getCurrencyCode()).isEqualTo("BRL");
        assertThat(found.get().getUserId()).isEqualTo(testUserId);
        assertThat(found.get().getCreatedAt()).isNotNull();
        assertThat(found.get().getUpdatedAt()).isNotNull();
    }

    @Test
    void allAccountTypesAreStoredAsStrings() {
        for (AccountType type : AccountType.values()) {
            Account account = new Account();
            account.setUserId(testUserId);
            account.setName("Account " + type.name() + UUID.randomUUID());
            account.setType(type);

            Account saved = accountRepository.save(account);
            accountRepository.flush();

            Optional<Account> found = accountRepository.findById(saved.getId());
            assertThat(found).isPresent();
            assertThat(found.get().getType()).isEqualTo(type);
        }
    }

    @Test
    void findAllByUserIdAndDeletedAtIsNull_excludesDeletedAccounts() {
        Account active = new Account();
        active.setUserId(testUserId);
        active.setName("Active Account");
        active.setType(AccountType.CHECKING);
        accountRepository.save(active);

        Account deleted = new Account();
        deleted.setUserId(testUserId);
        deleted.setName("Deleted Account");
        deleted.setType(AccountType.SAVINGS);
        deleted.setDeletedAt(java.time.Instant.now());
        accountRepository.save(deleted);

        List<Account> result = accountRepository.findAllByUserIdAndDeletedAtIsNull(testUserId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Active Account");
    }

    @Test
    void findByIdAndUserIdAndDeletedAtIsNull_returnsEmptyForOtherUser() {
        Account account = new Account();
        account.setUserId(testUserId);
        account.setName("Test Account");
        account.setType(AccountType.CASH);
        Account saved = accountRepository.save(account);

        UUID otherUserId = UUID.randomUUID();
        Optional<Account> result = accountRepository.findByIdAndUserIdAndDeletedAtIsNull(saved.getId(), otherUserId);

        assertThat(result).isEmpty();
    }

    @Test
    void existsByUserIdAndNameAndDeletedAtIsNull_detectsDuplicateName() {
        Account account = new Account();
        account.setUserId(testUserId);
        account.setName("Unique Name");
        account.setType(AccountType.CHECKING);
        accountRepository.save(account);

        boolean exists = accountRepository.existsByUserIdAndNameAndDeletedAtIsNull(testUserId, "Unique Name");
        assertThat(exists).isTrue();

        boolean notExists = accountRepository.existsByUserIdAndNameAndDeletedAtIsNull(testUserId, "Other Name");
        assertThat(notExists).isFalse();
    }

    @Test
    void archivedAccountIsStillRetrievable() {
        Account account = new Account();
        account.setUserId(testUserId);
        account.setName("Archived Account");
        account.setType(AccountType.SAVINGS);
        account.setArchivedAt(java.time.Instant.now());
        Account saved = accountRepository.save(account);

        Optional<Account> found = accountRepository.findByIdAndUserIdAndDeletedAtIsNull(saved.getId(), testUserId);
        assertThat(found).isPresent();
        assertThat(found.get().getArchivedAt()).isNotNull();
    }
}
