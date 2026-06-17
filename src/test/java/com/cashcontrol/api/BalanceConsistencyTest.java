package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateTransactionRequest;
import com.cashcontrol.api.dto.response.AccountResponse;
import com.cashcontrol.api.dto.response.TransactionDetailResponse;
import com.cashcontrol.api.service.AccountService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class BalanceConsistencyTest {

    @Autowired private TransactionService transactionService;
    @Autowired private AccountService accountService;
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
                "balance-consistency-" + UUID.randomUUID() + "@example.com");

        AccountResponse account = accountService.createAccount(
                new CreateAccountRequest("Test Account", AccountType.CHECKING, "BRL", null, 0, null),
                userId);
        accountId = account.id();
    }

    @Test
    void income_increasesBalance() {
        BigDecimal before = accountService.computeBalance(accountId, userId);

        transactionService.createTransaction(
                new CreateTransactionRequest(
                        accountId, TransactionType.INCOME,
                        new BigDecimal("500.00"), "Salary",
                        LocalDate.now(), null, null, null, null, null, null, null, null, null),
                userId);

        BigDecimal after = accountService.computeBalance(accountId, userId);
        assertThat(after).isEqualByComparingTo(before.add(new BigDecimal("500.00")));
    }

    @Test
    void expense_decreasesBalance() {
        transactionService.createTransaction(
                new CreateTransactionRequest(
                        accountId, TransactionType.INCOME,
                        new BigDecimal("1000.00"), "Income",
                        LocalDate.now(), null, null, null, null, null, null, null, null, null),
                userId);

        BigDecimal afterIncome = accountService.computeBalance(accountId, userId);

        transactionService.createTransaction(
                new CreateTransactionRequest(
                        accountId, TransactionType.EXPENSE,
                        new BigDecimal("200.00"), "Rent",
                        LocalDate.now(), null, null, null, null, null, null, null, null, null),
                userId);

        BigDecimal afterExpense = accountService.computeBalance(accountId, userId);
        assertThat(afterExpense).isEqualByComparingTo(afterIncome.subtract(new BigDecimal("200.00")));
    }

    @Test
    void refund_increasesBalance() {
        transactionService.createTransaction(
                new CreateTransactionRequest(
                        accountId, TransactionType.INCOME,
                        new BigDecimal("1000.00"), "Income",
                        LocalDate.now(), null, null, null, null, null, null, null, null, null),
                userId);
        transactionService.createTransaction(
                new CreateTransactionRequest(
                        accountId, TransactionType.EXPENSE,
                        new BigDecimal("200.00"), "Purchase",
                        LocalDate.now(), null, null, null, null, null, null, null, null, null),
                userId);

        BigDecimal before = accountService.computeBalance(accountId, userId);

        transactionService.createTransaction(
                new CreateTransactionRequest(
                        accountId, TransactionType.REFUND,
                        new BigDecimal("50.00"), "Partial refund",
                        LocalDate.now(), null, null, null, null, null, null, null, null, null),
                userId);

        BigDecimal after = accountService.computeBalance(accountId, userId);
        assertThat(after).isEqualByComparingTo(before.add(new BigDecimal("50.00")));
    }

    @Test
    void pendingTransaction_doesNotAffectBalance() {
        transactionService.createTransaction(
                new CreateTransactionRequest(
                        accountId, TransactionType.INCOME,
                        new BigDecimal("1000.00"), "Income",
                        LocalDate.now(), null, null, null, null, null, null, null, null, null),
                userId);

        BigDecimal afterIncome = accountService.computeBalance(accountId, userId);

        transactionService.createTransaction(
                new CreateTransactionRequest(
                        accountId, TransactionType.EXPENSE,
                        new BigDecimal("200.00"), "Pending Rent",
                        LocalDate.now(), null, null, null, null, null, null,
                        TransactionStatus.PENDING, null, null),
                userId);

        BigDecimal afterPending = accountService.computeBalance(accountId, userId);
        assertThat(afterPending).isEqualByComparingTo(afterIncome);
    }

    @Test
    void cancelledPaidTransaction_removesItsBalanceContribution() {
        transactionService.createTransaction(
                new CreateTransactionRequest(
                        accountId, TransactionType.INCOME,
                        new BigDecimal("1000.00"), "Income",
                        LocalDate.now(), null, null, null, null, null, null, null, null, null),
                userId);

        BigDecimal afterIncome = accountService.computeBalance(accountId, userId);

        TransactionDetailResponse expense = transactionService.createTransaction(
                new CreateTransactionRequest(
                        accountId, TransactionType.EXPENSE,
                        new BigDecimal("300.00"), "Expense",
                        LocalDate.now(), null, null, null, null, null, null, null, null, null),
                userId);

        transactionService.cancelTransaction(expense.id(), userId);

        BigDecimal afterCancel = accountService.computeBalance(accountId, userId);
        assertThat(afterCancel).isEqualByComparingTo(afterIncome);
    }

    @Test
    void multipleTransactions_balanceIsCorrect() {
        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.INCOME, new BigDecimal("2000.00"), "Salary",
                LocalDate.now(), null, null, null, null, null, null, null, null, null), userId);

        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.EXPENSE, new BigDecimal("500.00"), "Rent",
                LocalDate.now(), null, null, null, null, null, null, null, null, null), userId);

        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.EXPENSE, new BigDecimal("100.00"), "Groceries",
                LocalDate.now(), null, null, null, null, null, null, null, null, null), userId);

        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.REFUND, new BigDecimal("25.00"), "Refund",
                LocalDate.now(), null, null, null, null, null, null, null, null, null), userId);

        BigDecimal balance = accountService.computeBalance(accountId, userId);
        // 2000 - 500 - 100 + 25 = 1425
        assertThat(balance).isEqualByComparingTo(new BigDecimal("1425.00"));
    }
}
