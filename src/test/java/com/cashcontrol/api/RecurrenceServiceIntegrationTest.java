package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.DeleteRecurrenceStrategy;
import com.cashcontrol.api.domain.entity.RecurrenceFrequency;
import com.cashcontrol.api.domain.entity.RecurrenceStatus;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateRecurrenceRequest;
import com.cashcontrol.api.dto.request.EditRecurrenceRequest;
import com.cashcontrol.api.dto.request.PauseRecurrenceRequest;
import com.cashcontrol.api.dto.response.AccountResponse;
import com.cashcontrol.api.dto.response.DeleteRecurrenceResult;
import com.cashcontrol.api.dto.response.EditRecurrenceResult;
import com.cashcontrol.api.dto.response.RecurrenceCreationResponse;
import com.cashcontrol.api.dto.response.RecurrenceRuleResponse;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.RecurrenceService;
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
class RecurrenceServiceIntegrationTest {

    @Autowired private RecurrenceService recurrenceService;
    @Autowired private AccountService accountService;
    @Autowired private TransactionRepository transactionRepository;
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
                "recurrence-integration-" + UUID.randomUUID() + "@example.com");

        AccountResponse account = accountService.createAccount(
                new CreateAccountRequest("Test Account", AccountType.CHECKING, "BRL", null, 0, null),
                userId);
        accountId = account.id();
    }

    @Test
    void createRecurrence_createsRuleAndFirstInstance() {
        LocalDate startDate = LocalDate.now().plusDays(1);
        RecurrenceCreationResponse response = recurrenceService.createRecurrence(
                new CreateRecurrenceRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("500.00"), "Monthly rent", null, null,
                        startDate, null, RecurrenceFrequency.MONTHLY),
                userId);

        assertThat(response.rule()).isNotNull();
        assertThat(response.rule().status()).isEqualTo(RecurrenceStatus.ACTIVE);
        assertThat(response.rule().frequency()).isEqualTo(RecurrenceFrequency.MONTHLY);
        assertThat(response.rule().amount()).isEqualByComparingTo(new BigDecimal("500.00"));

        assertThat(response.firstInstance()).isNotNull();
        assertThat(response.firstInstance().status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(response.firstInstance().competenceDate()).isEqualTo(startDate);
    }

    @Test
    void createRecurrence_preGeneratesUpTo12Instances() {
        LocalDate startDate = LocalDate.now().plusDays(1);
        RecurrenceCreationResponse response = recurrenceService.createRecurrence(
                new CreateRecurrenceRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("100.00"), "Weekly expense", null, null,
                        startDate, null, RecurrenceFrequency.MONTHLY),
                userId);

        UUID ruleId = response.rule().id();
        long count = transactionRepository.findAllByRecurrenceRule_Id(ruleId).size();
        assertThat(count).isEqualTo(12);
    }

    @Test
    void createRecurrence_startDateToday_firstInstanceIsPaid() {
        LocalDate today = LocalDate.now();
        RecurrenceCreationResponse response = recurrenceService.createRecurrence(
                new CreateRecurrenceRequest(accountId, TransactionType.INCOME,
                        new BigDecimal("3000.00"), "Salary", null, null,
                        today, null, RecurrenceFrequency.MONTHLY),
                userId);

        assertThat(response.firstInstance().status()).isEqualTo(TransactionStatus.PAID);
        assertThat(response.firstInstance().paymentDate()).isEqualTo(today);
    }

    @Test
    void createRecurrence_withEndDate_stopsGeneratingAfterEndDate() {
        LocalDate startDate = LocalDate.now().plusMonths(1);
        LocalDate endDate = startDate.plusMonths(2);

        RecurrenceCreationResponse response = recurrenceService.createRecurrence(
                new CreateRecurrenceRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("200.00"), "Subscription", null, null,
                        startDate, endDate, RecurrenceFrequency.MONTHLY),
                userId);

        UUID ruleId = response.rule().id();
        long count = transactionRepository.findAllByRecurrenceRule_Id(ruleId).size();
        // startDate, startDate+1month, startDate+2months = 3 total (but last is on endDate exactly)
        assertThat(count).isGreaterThanOrEqualTo(1).isLessThanOrEqualTo(3);

        // Rule should eventually be ENDED since all periods have been generated
        assertThat(response.rule().status()).isIn(RecurrenceStatus.ACTIVE, RecurrenceStatus.ENDED);
    }

    @Test
    void createRecurrence_archivedAccount_throwsBusinessRuleException() {
        accountService.archiveAccount(accountId, userId);

        assertThatThrownBy(() -> recurrenceService.createRecurrence(
                new CreateRecurrenceRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("100.00"), "Rent", null, null,
                        LocalDate.now(), null, RecurrenceFrequency.MONTHLY),
                userId))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void editSeries_updatesRuleAndFutureInstances() {
        LocalDate startDate = LocalDate.now().plusDays(1);
        RecurrenceCreationResponse created = recurrenceService.createRecurrence(
                new CreateRecurrenceRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("500.00"), "Monthly rent", null, null,
                        startDate, null, RecurrenceFrequency.MONTHLY),
                userId);

        UUID ruleId = created.rule().id();
        EditRecurrenceResult result = recurrenceService.editSeries(ruleId,
                new EditRecurrenceRequest(new BigDecimal("600.00"), "Updated rent", null, null, null),
                userId);

        assertThat(result.rule().amount()).isEqualByComparingTo(new BigDecimal("600.00"));
        assertThat(result.rule().description()).isEqualTo("Updated rent");
        assertThat(result.updatedInstances()).isGreaterThanOrEqualTo(1);

        transactionRepository.findAllByRecurrenceRule_Id(ruleId).stream()
                .filter(tx -> tx.getStatus() == TransactionStatus.PENDING)
                .forEach(tx -> {
                    assertThat(tx.getAmount()).isEqualByComparingTo(new BigDecimal("600.00"));
                    assertThat(tx.getDescription()).isEqualTo("Updated rent");
                });
    }

    @Test
    void editSeries_deletedRule_throwsResourceNotFoundException() {
        RecurrenceCreationResponse created = recurrenceService.createRecurrence(
                new CreateRecurrenceRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("100.00"), "Rent", null, null,
                        LocalDate.now().plusDays(1), null, RecurrenceFrequency.MONTHLY),
                userId);

        UUID ruleId = created.rule().id();
        recurrenceService.deleteRecurrence(ruleId, DeleteRecurrenceStrategy.FUTURE_ONLY, userId);

        assertThatThrownBy(() -> recurrenceService.editSeries(ruleId,
                new EditRecurrenceRequest(new BigDecimal("200.00"), null, null, null, null),
                userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void pauseRecurrence_cancelsFuturePendingInstances() {
        LocalDate startDate = LocalDate.now().plusDays(1);
        RecurrenceCreationResponse created = recurrenceService.createRecurrence(
                new CreateRecurrenceRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("500.00"), "Subscription", null, null,
                        startDate, null, RecurrenceFrequency.MONTHLY),
                userId);

        UUID ruleId = created.rule().id();
        long pendingBefore = transactionRepository.findAllByRecurrenceRule_Id(ruleId).stream()
                .filter(tx -> tx.getStatus() == TransactionStatus.PENDING).count();
        assertThat(pendingBefore).isGreaterThan(0);

        RecurrenceRuleResponse paused = recurrenceService.pauseRecurrence(ruleId,
                new PauseRecurrenceRequest(null), userId);

        assertThat(paused.status()).isEqualTo(RecurrenceStatus.PAUSED);

        long pendingAfter = transactionRepository.findAllByRecurrenceRule_Id(ruleId).stream()
                .filter(tx -> tx.getStatus() == TransactionStatus.PENDING).count();
        assertThat(pendingAfter).isEqualTo(0);

        long cancelledCount = transactionRepository.findAllByRecurrenceRule_Id(ruleId).stream()
                .filter(tx -> tx.getStatus() == TransactionStatus.CANCELLED).count();
        assertThat(cancelledCount).isEqualTo(pendingBefore);
    }

    @Test
    void pauseRecurrence_notActive_throwsBusinessRuleException() {
        RecurrenceCreationResponse created = recurrenceService.createRecurrence(
                new CreateRecurrenceRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("100.00"), "Rent", null, null,
                        LocalDate.now().plusDays(1), null, RecurrenceFrequency.MONTHLY),
                userId);

        UUID ruleId = created.rule().id();
        recurrenceService.pauseRecurrence(ruleId, new PauseRecurrenceRequest(null), userId);

        assertThatThrownBy(() -> recurrenceService.pauseRecurrence(ruleId,
                new PauseRecurrenceRequest(null), userId))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void resumeRecurrence_regeneratesInstances() {
        LocalDate startDate = LocalDate.now().plusDays(1);
        RecurrenceCreationResponse created = recurrenceService.createRecurrence(
                new CreateRecurrenceRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("500.00"), "Subscription", null, null,
                        startDate, null, RecurrenceFrequency.MONTHLY),
                userId);

        UUID ruleId = created.rule().id();
        recurrenceService.pauseRecurrence(ruleId, new PauseRecurrenceRequest(null), userId);

        long countAfterPause = transactionRepository.findAllByRecurrenceRule_Id(ruleId).stream()
                .filter(tx -> tx.getStatus() == TransactionStatus.PENDING).count();
        assertThat(countAfterPause).isEqualTo(0);

        RecurrenceRuleResponse resumed = recurrenceService.resumeRecurrence(ruleId, userId);
        assertThat(resumed.status()).isEqualTo(RecurrenceStatus.ACTIVE);

        long countAfterResume = transactionRepository.findAllByRecurrenceRule_Id(ruleId).stream()
                .filter(tx -> tx.getStatus() == TransactionStatus.PENDING).count();
        assertThat(countAfterResume).isGreaterThan(0);
    }

    @Test
    void resumeRecurrence_notPaused_throwsBusinessRuleException() {
        RecurrenceCreationResponse created = recurrenceService.createRecurrence(
                new CreateRecurrenceRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("100.00"), "Rent", null, null,
                        LocalDate.now().plusDays(1), null, RecurrenceFrequency.MONTHLY),
                userId);

        UUID ruleId = created.rule().id();

        assertThatThrownBy(() -> recurrenceService.resumeRecurrence(ruleId, userId))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void deleteRecurrence_futureOnly_cancelsOnlyFutureInstances() {
        LocalDate startDate = LocalDate.now().plusDays(1);
        RecurrenceCreationResponse created = recurrenceService.createRecurrence(
                new CreateRecurrenceRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("500.00"), "Subscription", null, null,
                        startDate, null, RecurrenceFrequency.MONTHLY),
                userId);

        UUID ruleId = created.rule().id();
        long totalBefore = transactionRepository.findAllByRecurrenceRule_Id(ruleId).size();

        DeleteRecurrenceResult result = recurrenceService.deleteRecurrence(
                ruleId, DeleteRecurrenceStrategy.FUTURE_ONLY, userId);

        assertThat(result.cancelledInstances()).isEqualTo((int) totalBefore);

        long cancelledAfter = transactionRepository.findAllByRecurrenceRule_Id(ruleId).stream()
                .filter(tx -> tx.getStatus() == TransactionStatus.CANCELLED).count();
        assertThat(cancelledAfter).isEqualTo(totalBefore);
    }

    @Test
    void deleteRecurrence_all_cancelsAllPendingInstances() {
        LocalDate startDate = LocalDate.now().plusDays(1);
        RecurrenceCreationResponse created = recurrenceService.createRecurrence(
                new CreateRecurrenceRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("500.00"), "Subscription", null, null,
                        startDate, null, RecurrenceFrequency.MONTHLY),
                userId);

        UUID ruleId = created.rule().id();

        DeleteRecurrenceResult result = recurrenceService.deleteRecurrence(
                ruleId, DeleteRecurrenceStrategy.ALL, userId);

        assertThat(result.cancelledInstances()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void deleteRecurrence_neverTouchesPaidInstances() {
        LocalDate today = LocalDate.now();
        RecurrenceCreationResponse created = recurrenceService.createRecurrence(
                new CreateRecurrenceRequest(accountId, TransactionType.INCOME,
                        new BigDecimal("3000.00"), "Salary", null, null,
                        today, null, RecurrenceFrequency.MONTHLY),
                userId);

        UUID ruleId = created.rule().id();
        long paidBefore = transactionRepository.findAllByRecurrenceRule_Id(ruleId).stream()
                .filter(tx -> tx.getStatus() == TransactionStatus.PAID).count();

        recurrenceService.deleteRecurrence(ruleId, DeleteRecurrenceStrategy.ALL, userId);

        long paidAfter = transactionRepository.findAllByRecurrenceRule_Id(ruleId).stream()
                .filter(tx -> tx.getStatus() == TransactionStatus.PAID).count();
        assertThat(paidAfter).isEqualTo(paidBefore);
    }

    @Test
    void deleteRecurrence_softDeletesRule() {
        RecurrenceCreationResponse created = recurrenceService.createRecurrence(
                new CreateRecurrenceRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("100.00"), "Rent", null, null,
                        LocalDate.now().plusDays(1), null, RecurrenceFrequency.MONTHLY),
                userId);

        UUID ruleId = created.rule().id();
        recurrenceService.deleteRecurrence(ruleId, DeleteRecurrenceStrategy.FUTURE_ONLY, userId);

        assertThatThrownBy(() -> recurrenceService.getRecurrence(ruleId, userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listRecurrences_returnsOnlyNonDeletedRules() {
        recurrenceService.createRecurrence(
                new CreateRecurrenceRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("100.00"), "Rent", null, null,
                        LocalDate.now().plusDays(1), null, RecurrenceFrequency.MONTHLY),
                userId);

        RecurrenceCreationResponse toDelete = recurrenceService.createRecurrence(
                new CreateRecurrenceRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("50.00"), "Netflix", null, null,
                        LocalDate.now().plusDays(1), null, RecurrenceFrequency.MONTHLY),
                userId);

        recurrenceService.deleteRecurrence(toDelete.rule().id(), DeleteRecurrenceStrategy.FUTURE_ONLY, userId);

        List<RecurrenceRuleResponse> rules = recurrenceService.listRecurrences(userId);
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).description()).isEqualTo("Rent");
    }

    @Test
    void getRecurrence_otherUser_throwsResourceNotFoundException() {
        RecurrenceCreationResponse created = recurrenceService.createRecurrence(
                new CreateRecurrenceRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("100.00"), "Rent", null, null,
                        LocalDate.now().plusDays(1), null, RecurrenceFrequency.MONTHLY),
                userId);

        UUID otherUser = UUID.randomUUID();
        assertThatThrownBy(() -> recurrenceService.getRecurrence(created.rule().id(), otherUser))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
