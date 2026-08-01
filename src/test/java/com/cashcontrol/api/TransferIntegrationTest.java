package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class TransferIntegrationTest {

    @Autowired private AccountService accountService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private AccountResponse sourceAccount;
    private AccountResponse destinationAccount;

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
                "transfer-integration-" + UUID.randomUUID() + "@example.com");

        sourceAccount = accountService.createAccount(
                new CreateAccountRequest("Source", AccountType.CHECKING, "BRL", null, 0,
                        new BigDecimal("1000.00")), userId);

        destinationAccount = accountService.createAccount(
                new CreateAccountRequest("Destination", AccountType.SAVINGS, "BRL", null, 1,
                        BigDecimal.ZERO), userId);
    }

    @Test
    void createTransfer_movesBalanceBetweenAccounts() {
        TransferRequest request = new TransferRequest(
                sourceAccount.id(), destinationAccount.id(),
                new BigDecimal("300.00"), "Transfer test", LocalDate.now());

        accountService.createTransfer(request, userId);

        AccountResponse updatedSource = accountService.getAccount(sourceAccount.id(), userId);
        AccountResponse updatedDestination = accountService.getAccount(destinationAccount.id(), userId);

        assertThat(updatedSource.balance()).isEqualByComparingTo(new BigDecimal("700.00"));
        assertThat(updatedDestination.balance()).isEqualByComparingTo(new BigDecimal("300.00"));
    }

    @Test
    void createTransfer_portfolioBalanceUnchanged() {
        BigDecimal initialTotal = sourceAccount.balance().add(destinationAccount.balance());

        TransferRequest request = new TransferRequest(
                sourceAccount.id(), destinationAccount.id(),
                new BigDecimal("500.00"), "Portfolio test", LocalDate.now());

        accountService.createTransfer(request, userId);

        AccountResponse updatedSource = accountService.getAccount(sourceAccount.id(), userId);
        AccountResponse updatedDestination = accountService.getAccount(destinationAccount.id(), userId);

        BigDecimal finalTotal = updatedSource.balance().add(updatedDestination.balance());
        assertThat(finalTotal).isEqualByComparingTo(initialTotal);
    }

    @Test
    void createTransfer_sameAccount_throwsBusinessRuleException() {
        TransferRequest request = new TransferRequest(
                sourceAccount.id(), sourceAccount.id(),
                new BigDecimal("100.00"), null, null);

        assertThatThrownBy(() -> accountService.createTransfer(request, userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("diferentes");
    }

    @Test
    void createTransfer_archivedSource_throwsBusinessRuleException() {
        accountService.archiveAccount(sourceAccount.id(), userId);

        TransferRequest request = new TransferRequest(
                sourceAccount.id(), destinationAccount.id(),
                new BigDecimal("100.00"), null, null);

        assertThatThrownBy(() -> accountService.createTransfer(request, userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("arquivad");
    }

    @Test
    void createTransfer_archivedDestination_throwsBusinessRuleException() {
        accountService.archiveAccount(destinationAccount.id(), userId);

        TransferRequest request = new TransferRequest(
                sourceAccount.id(), destinationAccount.id(),
                new BigDecimal("100.00"), null, null);

        assertThatThrownBy(() -> accountService.createTransfer(request, userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("arquivad");
    }

    @Test
    void createTransfer_otherUserAccount_throwsResourceNotFoundException() {
        UUID otherUserId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) " +
                "VALUES (?, " +
                "  (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), " +
                "  (SELECT id FROM auth_origins WHERE slug = 'LOCAL'), " +
                "  NOW()) " +
                "RETURNING id",
                UUID.class,
                "other-user-" + UUID.randomUUID() + "@example.com");

        AccountResponse otherAccount = accountService.createAccount(
                new CreateAccountRequest("Other", AccountType.CHECKING, "BRL", null, 0, null),
                otherUserId);

        TransferRequest request = new TransferRequest(
                sourceAccount.id(), otherAccount.id(),
                new BigDecimal("100.00"), null, null);

        assertThatThrownBy(() -> accountService.createTransfer(request, userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteTransfer_removesBothLegsAndRestoresBalance() {
        TransferRequest request = new TransferRequest(
                sourceAccount.id(), destinationAccount.id(),
                new BigDecimal("400.00"), "To delete", LocalDate.now());

        accountService.createTransfer(request, userId);

        AccountResponse afterTransfer = accountService.getAccount(sourceAccount.id(), userId);
        assertThat(afterTransfer.balance()).isEqualByComparingTo(new BigDecimal("600.00"));

        UUID groupId = jdbcTemplate.queryForObject(
                "SELECT transfer_group_id FROM transactions WHERE account_id = ? AND type = 'TRANSFER' LIMIT 1",
                UUID.class, sourceAccount.id());

        accountService.deleteTransfer(groupId, userId);

        AccountResponse afterDelete = accountService.getAccount(sourceAccount.id(), userId);
        assertThat(afterDelete.balance()).isEqualByComparingTo(new BigDecimal("1000.00"));

        AccountResponse destAfterDelete = accountService.getAccount(destinationAccount.id(), userId);
        assertThat(destAfterDelete.balance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void deleteTransfer_notFound_throwsResourceNotFoundException() {
        UUID nonExistentGroupId = UUID.randomUUID();

        assertThatThrownBy(() -> accountService.deleteTransfer(nonExistentGroupId, userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void multipleTransfers_balancesConsistent() {
        accountService.createTransfer(new TransferRequest(
                sourceAccount.id(), destinationAccount.id(),
                new BigDecimal("200.00"), "First", LocalDate.now()), userId);

        accountService.createTransfer(new TransferRequest(
                sourceAccount.id(), destinationAccount.id(),
                new BigDecimal("300.00"), "Second", LocalDate.now()), userId);

        accountService.createTransfer(new TransferRequest(
                destinationAccount.id(), sourceAccount.id(),
                new BigDecimal("100.00"), "Return", LocalDate.now()), userId);

        AccountResponse finalSource = accountService.getAccount(sourceAccount.id(), userId);
        AccountResponse finalDest = accountService.getAccount(destinationAccount.id(), userId);

        // Source: 1000 - 200 - 300 + 100 = 600
        assertThat(finalSource.balance()).isEqualByComparingTo(new BigDecimal("600.00"));
        // Destination: 0 + 200 + 300 - 100 = 400
        assertThat(finalDest.balance()).isEqualByComparingTo(new BigDecimal("400.00"));
        // Portfolio unchanged: 1000
        assertThat(finalSource.balance().add(finalDest.balance()))
                .isEqualByComparingTo(new BigDecimal("1000.00"));
    }
}
