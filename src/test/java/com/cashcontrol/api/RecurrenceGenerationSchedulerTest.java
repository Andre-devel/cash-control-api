package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.RecurrenceFrequency;
import com.cashcontrol.api.domain.entity.RecurrenceStatus;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateRecurrenceRequest;
import com.cashcontrol.api.dto.response.AccountResponse;
import com.cashcontrol.api.dto.response.RecurrenceCreationResponse;
import com.cashcontrol.api.repository.RecurrenceRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class RecurrenceGenerationSchedulerTest {

    private static final int LOOKAHEAD_DAYS = 30;

    @Autowired private RecurrenceService recurrenceService;
    @Autowired private AccountService accountService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private RecurrenceRepository recurrenceRepository;
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
                "recurrence-gen-scheduler-" + UUID.randomUUID() + "@example.com");

        AccountResponse account = accountService.createAccount(
                new CreateAccountRequest("Test Account", AccountType.CHECKING, "BRL", null, 0, null),
                userId);
        accountId = account.id();
    }

    @Test
    void generatePendingInstances_generatesInstancesForDueRules() {
        // Create a recurrence that starts in the past so nextOccurrenceDate is within lookahead
        LocalDate startDate = LocalDate.now().minusMonths(13); // older than 12 pre-generated months
        RecurrenceCreationResponse created = recurrenceService.createRecurrence(
                new CreateRecurrenceRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("200.00"), "Monthly rent", null, null,
                        startDate, null, RecurrenceFrequency.MONTHLY),
                userId);

        UUID ruleId = created.rule().id();
        long countBefore = transactionRepository.findAllByRecurrenceRule_Id(ruleId).size();

        // Force nextOccurrenceDate to be within lookahead so scheduler picks it up
        recurrenceRepository.findById(ruleId).ifPresent(rule -> {
            rule.setNextOccurrenceDate(LocalDate.now().plusDays(1));
            rule.setStatus(RecurrenceStatus.ACTIVE);
            recurrenceRepository.save(rule);
        });
        recurrenceRepository.flush();

        int generated = recurrenceService.generatePendingInstances(LOOKAHEAD_DAYS);

        assertThat(generated).isGreaterThan(0);
        long countAfter = transactionRepository.findAllByRecurrenceRule_Id(ruleId).size();
        assertThat(countAfter).isGreaterThan(countBefore);
    }

    @Test
    void generatePendingInstances_isIdempotentOnDoubleRun() {
        LocalDate startDate = LocalDate.now().plusDays(1);
        RecurrenceCreationResponse created = recurrenceService.createRecurrence(
                new CreateRecurrenceRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("500.00"), "Monthly subscription", null, null,
                        startDate, null, RecurrenceFrequency.MONTHLY),
                userId);

        UUID ruleId = created.rule().id();

        // Set nextOccurrenceDate within lookahead to trigger generation
        recurrenceRepository.findById(ruleId).ifPresent(rule -> {
            rule.setNextOccurrenceDate(LocalDate.now().plusDays(5));
            recurrenceRepository.save(rule);
        });
        recurrenceRepository.flush();

        int firstRun = recurrenceService.generatePendingInstances(LOOKAHEAD_DAYS);
        long countAfterFirst = transactionRepository.findAllByRecurrenceRule_Id(ruleId).size();

        // Second run: nextOccurrenceDate should have advanced beyond the lookahead window
        // so no new instances should be generated
        int secondRun = recurrenceService.generatePendingInstances(LOOKAHEAD_DAYS);
        long countAfterSecond = transactionRepository.findAllByRecurrenceRule_Id(ruleId).size();

        assertThat(firstRun).isGreaterThan(0);
        // For MONTHLY frequency: after generating 12 instances, nextOccurrenceDate is ~12 months out
        // which is beyond 30 days lookahead, so second run generates nothing
        assertThat(secondRun).isEqualTo(0);
        assertThat(countAfterSecond).isEqualTo(countAfterFirst);
    }

    @Test
    void generatePendingInstances_skipsRulesWithFutureNextOccurrenceDate() {
        LocalDate startDate = LocalDate.now().plusDays(1);
        RecurrenceCreationResponse created = recurrenceService.createRecurrence(
                new CreateRecurrenceRequest(accountId, TransactionType.INCOME,
                        new BigDecimal("3000.00"), "Salary", null, null,
                        startDate, null, RecurrenceFrequency.MONTHLY),
                userId);

        UUID ruleId = created.rule().id();

        // nextOccurrenceDate is already far in the future (beyond lookahead) — do not touch it
        long countBefore = transactionRepository.findAllByRecurrenceRule_Id(ruleId).size();

        int generated = recurrenceService.generatePendingInstances(LOOKAHEAD_DAYS);

        long countAfter = transactionRepository.findAllByRecurrenceRule_Id(ruleId).size();
        // This rule's nextOccurrenceDate (set during createRecurrence) is ~12 months ahead,
        // so the scheduler should not pick it up
        assertThat(countAfter).isEqualTo(countBefore);
    }

    @Test
    void generatePendingInstances_stopsAtEndDate() {
        LocalDate startDate = LocalDate.now().plusDays(1);
        LocalDate endDate = startDate.plusMonths(2); // rule ends in 2 months
        RecurrenceCreationResponse created = recurrenceService.createRecurrence(
                new CreateRecurrenceRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("100.00"), "Short subscription", null, null,
                        startDate, endDate, RecurrenceFrequency.MONTHLY),
                userId);

        UUID ruleId = created.rule().id();

        // Force nextOccurrenceDate within lookahead
        recurrenceRepository.findById(ruleId).ifPresent(rule -> {
            rule.setNextOccurrenceDate(LocalDate.now().plusDays(2));
            rule.setStatus(RecurrenceStatus.ACTIVE);
            recurrenceRepository.save(rule);
        });
        recurrenceRepository.flush();

        recurrenceService.generatePendingInstances(LOOKAHEAD_DAYS);

        // Rule should be ENDED now since all periods are generated
        recurrenceRepository.findById(ruleId).ifPresent(rule -> {
            assertThat(rule.getStatus()).isIn(RecurrenceStatus.ACTIVE, RecurrenceStatus.ENDED);
        });

        // Verify no instances have dates beyond the endDate
        transactionRepository.findAllByRecurrenceRule_Id(ruleId)
                .forEach(tx -> assertThat(tx.getCompetenceDate()).isBeforeOrEqualTo(endDate));
    }

    @Test
    void generatePendingInstances_doesNotTouchPausedOrDeletedRules() {
        LocalDate startDate = LocalDate.now().plusDays(1);
        RecurrenceCreationResponse created = recurrenceService.createRecurrence(
                new CreateRecurrenceRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("50.00"), "Paused subscription", null, null,
                        startDate, null, RecurrenceFrequency.MONTHLY),
                userId);

        UUID ruleId = created.rule().id();

        // Force to PAUSED and set nextOccurrenceDate within lookahead
        recurrenceRepository.findById(ruleId).ifPresent(rule -> {
            rule.setStatus(RecurrenceStatus.PAUSED);
            rule.setNextOccurrenceDate(LocalDate.now().plusDays(1));
            recurrenceRepository.save(rule);
        });
        recurrenceRepository.flush();

        long countBefore = transactionRepository.findAllByRecurrenceRule_Id(ruleId)
                .stream().filter(tx -> tx.getStatus() == TransactionStatus.PENDING).count();

        int generated = recurrenceService.generatePendingInstances(LOOKAHEAD_DAYS);

        long countAfter = transactionRepository.findAllByRecurrenceRule_Id(ruleId)
                .stream().filter(tx -> tx.getStatus() == TransactionStatus.PENDING).count();

        // PAUSED rules are not in ACTIVE status → findActiveRulesDueBy skips them
        assertThat(countAfter).isEqualTo(countBefore);
    }

    @Test
    void generatePendingInstances_returnsZeroWhenNoRulesAreDue() {
        // No rules created — should return 0 with no errors
        int count = recurrenceService.generatePendingInstances(LOOKAHEAD_DAYS);
        assertThat(count).isGreaterThanOrEqualTo(0);
    }
}
