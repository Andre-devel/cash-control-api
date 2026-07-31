package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ConflictException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.EditAccountRequest;
import com.cashcontrol.api.dto.request.ManualAdjustmentRequest;
import com.cashcontrol.api.dto.request.TransferRequest;
import com.cashcontrol.api.dto.response.AccountResponse;
import com.cashcontrol.api.service.AccountService;
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
class AccountIntegrationTest {

    @Autowired private AccountService accountService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID userId;

    @BeforeEach
    void createTestUser() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) " +
                "VALUES (?, " +
                "  (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), " +
                "  (SELECT id FROM auth_origins WHERE slug = 'LOCAL'), " +
                "  NOW()) " +
                "RETURNING id",
                UUID.class,
                "account-integration-" + UUID.randomUUID() + "@example.com");
    }

    @Test
    void createAccount_andRetrieve_checksBalance() {
        CreateAccountRequest request = new CreateAccountRequest(
                "Checking Account", AccountType.CHECKING, "BRL", "My main account", 0,
                new BigDecimal("1000.00"));

        AccountResponse created = accountService.createAccount(request, userId);

        assertThat(created.id()).isNotNull();
        assertThat(created.name()).isEqualTo("Checking Account");
        assertThat(created.type()).isEqualTo(AccountType.CHECKING);
        assertThat(created.currencyCode()).isEqualTo("BRL");
        assertThat(created.balance()).isEqualByComparingTo(new BigDecimal("1000.00"));

        AccountResponse fetched = accountService.getAccount(created.id(), userId);
        assertThat(fetched.id()).isEqualTo(created.id());
        assertThat(fetched.balance()).isEqualByComparingTo(new BigDecimal("1000.00"));
    }

    @Test
    void createAccount_duplicateName_throwsConflict() {
        CreateAccountRequest request = new CreateAccountRequest(
                "Savings", AccountType.SAVINGS, "BRL", null, 0, null);
        accountService.createAccount(request, userId);

        assertThatThrownBy(() -> accountService.createAccount(request, userId))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void listAccounts_defaultExcludesArchived() {
        accountService.createAccount(new CreateAccountRequest("Active", AccountType.CHECKING, "BRL", null, 0, null), userId);
        AccountResponse archived = accountService.createAccount(
                new CreateAccountRequest("Archived", AccountType.SAVINGS, "BRL", null, 1, null), userId);
        accountService.archiveAccount(archived.id(), userId);

        List<AccountResponse> active = accountService.listAccounts(userId, false);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).name()).isEqualTo("Active");

        List<AccountResponse> all = accountService.listAccounts(userId, true);
        assertThat(all).hasSize(2);
    }

    @Test
    void listAccounts_sortedBySortOrderThenCreatedAt() {
        accountService.createAccount(new CreateAccountRequest("B", AccountType.CHECKING, "BRL", null, 2, null), userId);
        accountService.createAccount(new CreateAccountRequest("A", AccountType.SAVINGS, "BRL", null, 1, null), userId);
        accountService.createAccount(new CreateAccountRequest("C", AccountType.CASH, "BRL", null, 3, null), userId);

        List<AccountResponse> accounts = accountService.listAccounts(userId, false);

        assertThat(accounts).extracting(AccountResponse::name)
                .containsExactly("A", "B", "C");
    }

    @Test
    void editAccount_updatesFieldsAndValidatesNameUniqueness() {
        AccountResponse account = accountService.createAccount(
                new CreateAccountRequest("Original", AccountType.CHECKING, "BRL", null, 0, null), userId);

        EditAccountRequest editRequest = new EditAccountRequest("Renamed", AccountType.SAVINGS, "USD", "desc", 5);
        AccountResponse edited = accountService.editAccount(account.id(), editRequest, userId);

        assertThat(edited.name()).isEqualTo("Renamed");
        assertThat(edited.type()).isEqualTo(AccountType.SAVINGS);
        assertThat(edited.currencyCode()).isEqualTo("USD");
        assertThat(edited.sortOrder()).isEqualTo(5);
    }

    @Test
    void editAccount_sameNameIsAllowed() {
        AccountResponse account = accountService.createAccount(
                new CreateAccountRequest("Same Name", AccountType.CHECKING, "BRL", null, 0, null), userId);

        EditAccountRequest editRequest = new EditAccountRequest("Same Name", AccountType.SAVINGS, "BRL", null, 0);
        AccountResponse edited = accountService.editAccount(account.id(), editRequest, userId);

        assertThat(edited.name()).isEqualTo("Same Name");
    }

    @Test
    void archiveAndUnarchive_lifecycle() {
        AccountResponse account = accountService.createAccount(
                new CreateAccountRequest("My Account", AccountType.CHECKING, "BRL", null, 0, null), userId);

        assertThat(account.archivedAt()).isNull();

        AccountResponse archived = accountService.archiveAccount(account.id(), userId);
        assertThat(archived.archivedAt()).isNotNull();

        assertThatThrownBy(() -> accountService.archiveAccount(account.id(), userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("já está arquivad");

        AccountResponse unarchived = accountService.unarchiveAccount(account.id(), userId);
        assertThat(unarchived.archivedAt()).isNull();

        assertThatThrownBy(() -> accountService.unarchiveAccount(account.id(), userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não está arquivada");
    }

    @Test
    void deleteAccount_withNoTransactions_succeeds() {
        AccountResponse account = accountService.createAccount(
                new CreateAccountRequest("Empty Account", AccountType.CHECKING, "BRL", null, 0, null), userId);

        accountService.deleteAccount(account.id(), userId);

        assertThatThrownBy(() -> accountService.getAccount(account.id(), userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteAccount_withSeedOnly_succeeds() {
        AccountResponse account = accountService.createAccount(
                new CreateAccountRequest("Seed Only", AccountType.CHECKING, "BRL", null, 0,
                        new BigDecimal("100.00")), userId);

        accountService.deleteAccount(account.id(), userId);

        assertThatThrownBy(() -> accountService.getAccount(account.id(), userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteAccount_withExtraTransactions_throwsBusinessRuleException() {
        AccountResponse account = accountService.createAccount(
                new CreateAccountRequest("Active Account", AccountType.CHECKING, "BRL", null, 0,
                        new BigDecimal("500.00")), userId);

        accountService.manualAdjustment(account.id(),
                new ManualAdjustmentRequest(new BigDecimal("100.00"), "Extra adjustment", LocalDate.now()),
                userId);

        assertThatThrownBy(() -> accountService.deleteAccount(account.id(), userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("possui transações");
    }

    @Test
    void manualAdjustment_updatesBalance() {
        AccountResponse account = accountService.createAccount(
                new CreateAccountRequest("My Account", AccountType.CHECKING, "BRL", null, 0,
                        new BigDecimal("100.00")), userId);

        AccountResponse afterPositive = accountService.manualAdjustment(account.id(),
                new ManualAdjustmentRequest(new BigDecimal("50.00"), "Deposit", LocalDate.now()),
                userId);
        assertThat(afterPositive.balance()).isEqualByComparingTo(new BigDecimal("150.00"));

        AccountResponse afterNegative = accountService.manualAdjustment(account.id(),
                new ManualAdjustmentRequest(new BigDecimal("-30.00"), "Withdrawal", LocalDate.now()),
                userId);
        assertThat(afterNegative.balance()).isEqualByComparingTo(new BigDecimal("120.00"));
    }

    @Test
    void getAccount_otherUserId_throwsResourceNotFoundException() {
        AccountResponse account = accountService.createAccount(
                new CreateAccountRequest("My Account", AccountType.CHECKING, "BRL", null, 0, null), userId);

        UUID otherUser = UUID.randomUUID();
        assertThatThrownBy(() -> accountService.getAccount(account.id(), otherUser))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
