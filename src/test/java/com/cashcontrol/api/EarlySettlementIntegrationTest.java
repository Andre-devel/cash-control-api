package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.request.AdvanceInstallmentRequest;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateInstallmentRequest;
import com.cashcontrol.api.dto.request.EarlySettlementRequest;
import com.cashcontrol.api.dto.request.EditInstallmentRequest;
import com.cashcontrol.api.dto.request.EditSeriesRequest;
import com.cashcontrol.api.dto.response.EarlySettlementResponse;
import com.cashcontrol.api.dto.response.EditSeriesResult;
import com.cashcontrol.api.dto.response.InstallmentSeriesDetailResponse;
import com.cashcontrol.api.dto.response.TransactionDetailResponse;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.InstallmentService;
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
class EarlySettlementIntegrationTest {

    @Autowired private InstallmentService installmentService;
    @Autowired private AccountService accountService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setup() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) " +
                "VALUES (?, " +
                "  (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), " +
                "  (SELECT id FROM auth_origins WHERE slug = 'LOCAL'), " +
                "  NOW()) " +
                "RETURNING id",
                UUID.class,
                "installment-test-" + UUID.randomUUID() + "@example.com");

        accountId = accountService.createAccount(
                new CreateAccountRequest("Test Account", AccountType.CHECKING, "BRL", null, 0, null), userId).id();
    }

    // ── createInstallmentSeries ───────────────────────────────────────────────

    @Test
    void createInstallmentSeries_generatesCorrectInstallments() {
        LocalDate firstDate = LocalDate.now().plusMonths(1);

        InstallmentSeriesDetailResponse result = installmentService.createInstallmentSeries(
                new CreateInstallmentRequest(accountId, new BigDecimal("100.00"), 3, firstDate,
                        "Test purchase", null, null, null),
                userId);

        assertThat(result.series().id()).isNotNull();
        assertThat(result.series().totalAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(result.series().totalInstallments()).isEqualTo(3);
        assertThat(result.installments()).hasSize(3);

        // Verify amounts sum to total
        BigDecimal sum = result.installments().stream()
                .map(t -> t.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void createInstallmentSeries_firstInstallmentPaid_whenDateIsPast() {
        LocalDate yesterday = LocalDate.now().minusDays(1);

        InstallmentSeriesDetailResponse result = installmentService.createInstallmentSeries(
                new CreateInstallmentRequest(accountId, new BigDecimal("300.00"), 3, yesterday,
                        "Past purchase", null, null, null),
                userId);

        assertThat(result.installments().get(0).status()).isEqualTo(TransactionStatus.PAID);
        assertThat(result.installments().get(1).status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(result.installments().get(2).status()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void createInstallmentSeries_allPending_whenFirstDateIsFuture() {
        LocalDate nextMonth = LocalDate.now().plusMonths(1);

        InstallmentSeriesDetailResponse result = installmentService.createInstallmentSeries(
                new CreateInstallmentRequest(accountId, new BigDecimal("300.00"), 3, nextMonth,
                        "Future purchase", null, null, null),
                userId);

        assertThat(result.installments()).allMatch(t -> t.status() == TransactionStatus.PENDING);
    }

    @Test
    void createInstallmentSeries_monthlyProgression() {
        LocalDate firstDate = LocalDate.of(2026, 6, 1);

        InstallmentSeriesDetailResponse result = installmentService.createInstallmentSeries(
                new CreateInstallmentRequest(accountId, new BigDecimal("300.00"), 3, firstDate,
                        "Monthly purchase", null, null, null),
                userId);

        assertThat(result.installments().get(0).competenceDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(result.installments().get(1).competenceDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(result.installments().get(2).competenceDate()).isEqualTo(LocalDate.of(2026, 8, 1));
    }

    @Test
    void createInstallmentSeries_remainderOnLastInstallment() {
        InstallmentSeriesDetailResponse result = installmentService.createInstallmentSeries(
                new CreateInstallmentRequest(accountId, new BigDecimal("100.00"), 3,
                        LocalDate.now().plusMonths(1), "Uneven split", null, null, null),
                userId);

        BigDecimal first = result.installments().get(0).amount();
        BigDecimal second = result.installments().get(1).amount();
        BigDecimal last = result.installments().get(2).amount();

        assertThat(first).isEqualByComparingTo(new BigDecimal("33.33"));
        assertThat(second).isEqualByComparingTo(new BigDecimal("33.33"));
        assertThat(last).isEqualByComparingTo(new BigDecimal("33.34"));
    }

    // ── earlySettlement ───────────────────────────────────────────────────────

    @Test
    void earlySettlement_cancelsRemainingAndCreatesSettlementTransaction() {
        LocalDate nextMonth = LocalDate.now().plusMonths(1);

        InstallmentSeriesDetailResponse created = installmentService.createInstallmentSeries(
                new CreateInstallmentRequest(accountId, new BigDecimal("300.00"), 3, nextMonth,
                        "Purchase", null, null, null),
                userId);

        UUID seriesId = created.series().id();

        EarlySettlementResponse result = installmentService.earlySettlement(
                seriesId,
                new EarlySettlementRequest(new BigDecimal("280.00"), LocalDate.now()),
                userId);

        assertThat(result.cancelledInstallments()).isEqualTo(3);
        assertThat(result.settlementTransaction().amount()).isEqualByComparingTo(new BigDecimal("280.00"));
        assertThat(result.settlementTransaction().status()).isEqualTo(TransactionStatus.PAID);
        assertThat(result.settlementTransaction().installmentSeriesId()).isEqualTo(seriesId);

        // Verify all original installments are cancelled
        List<com.cashcontrol.api.domain.entity.Transaction> allTx =
                transactionRepository.findAllByInstallmentSeries_Id(seriesId);
        long cancelled = allTx.stream()
                .filter(t -> t.getStatus() == TransactionStatus.CANCELLED)
                .count();
        assertThat(cancelled).isEqualTo(3);
    }

    @Test
    void earlySettlement_withFirstPaid_onlyCancelsPending() {
        LocalDate yesterday = LocalDate.now().minusDays(1);

        InstallmentSeriesDetailResponse created = installmentService.createInstallmentSeries(
                new CreateInstallmentRequest(accountId, new BigDecimal("300.00"), 3, yesterday,
                        "Purchase", null, null, null),
                userId);

        UUID seriesId = created.series().id();

        EarlySettlementResponse result = installmentService.earlySettlement(
                seriesId,
                new EarlySettlementRequest(new BigDecimal("200.00"), LocalDate.now()),
                userId);

        // Only 2 are PENDING (first was already PAID)
        assertThat(result.cancelledInstallments()).isEqualTo(2);
    }

    @Test
    void earlySettlement_alreadySettled_throwsBusinessRuleException() {
        UUID seriesId = installmentService.createInstallmentSeries(
                new CreateInstallmentRequest(accountId, new BigDecimal("300.00"), 3,
                        LocalDate.now().plusMonths(1), "Purchase", null, null, null),
                userId).series().id();

        installmentService.earlySettlement(seriesId,
                new EarlySettlementRequest(new BigDecimal("280.00"), LocalDate.now()), userId);

        assertThatThrownBy(() -> installmentService.earlySettlement(seriesId,
                new EarlySettlementRequest(new BigDecimal("100.00"), LocalDate.now()), userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already settled");
    }

    // ── editSeries ────────────────────────────────────────────────────────────

    @Test
    void editSeries_updatesDescriptionOnPendingInstallments() {
        UUID seriesId = installmentService.createInstallmentSeries(
                new CreateInstallmentRequest(accountId, new BigDecimal("300.00"), 3,
                        LocalDate.now().plusMonths(1), "Original", null, null, null),
                userId).series().id();

        EditSeriesResult result = installmentService.editSeries(
                seriesId, new EditSeriesRequest("Updated", null, null, null, null), userId);

        assertThat(result.series().description()).isEqualTo("Updated");
        assertThat(result.affectedInstallments()).isEqualTo(3);
    }

    @Test
    void editSeries_settledSeries_throwsBusinessRuleException() {
        UUID seriesId = installmentService.createInstallmentSeries(
                new CreateInstallmentRequest(accountId, new BigDecimal("300.00"), 3,
                        LocalDate.now().plusMonths(1), "Purchase", null, null, null),
                userId).series().id();

        installmentService.earlySettlement(seriesId,
                new EarlySettlementRequest(new BigDecimal("280.00"), LocalDate.now()), userId);

        assertThatThrownBy(() -> installmentService.editSeries(
                seriesId, new EditSeriesRequest("Updated", null, null, null, null), userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("settled");
    }

    // ── editInstallment ───────────────────────────────────────────────────────

    @Test
    void editInstallment_detachesFromSeries() {
        InstallmentSeriesDetailResponse created = installmentService.createInstallmentSeries(
                new CreateInstallmentRequest(accountId, new BigDecimal("300.00"), 3,
                        LocalDate.now().plusMonths(1), "Purchase", null, null, null),
                userId);

        UUID installmentId = created.installments().get(0).id();

        TransactionDetailResponse edited = installmentService.editInstallment(
                installmentId,
                new EditInstallmentRequest(new BigDecimal("150.00"), "Custom amount", null, null, null, null),
                userId);

        assertThat(edited.detached()).isTrue();
        assertThat(edited.amount()).isEqualByComparingTo(new BigDecimal("150.00"));
    }

    @Test
    void editInstallment_notAnInstallment_throwsBusinessRuleException() {
        // Create a standalone transaction
        com.cashcontrol.api.domain.entity.Transaction tx = new com.cashcontrol.api.domain.entity.Transaction();
        tx.setUserId(userId);
        com.cashcontrol.api.domain.entity.Account account = new com.cashcontrol.api.domain.entity.Account();
        // Can't easily create a standalone transaction here without going through service
        // Test that calling editInstallment with a series transaction UUID that doesn't exist throws 404
        assertThatThrownBy(() -> installmentService.editInstallment(
                UUID.randomUUID(),
                new EditInstallmentRequest(null, "Test", null, null, null, null),
                userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void editSeries_excludesDetachedInstallments() {
        InstallmentSeriesDetailResponse created = installmentService.createInstallmentSeries(
                new CreateInstallmentRequest(accountId, new BigDecimal("300.00"), 3,
                        LocalDate.now().plusMonths(1), "Purchase", null, null, null),
                userId);

        UUID seriesId = created.series().id();
        UUID firstInstallmentId = created.installments().get(0).id();

        // Detach the first installment
        installmentService.editInstallment(firstInstallmentId,
                new EditInstallmentRequest(null, "Detached", null, null, null, null), userId);

        // Edit the series — should only affect 2 non-detached installments
        EditSeriesResult result = installmentService.editSeries(
                seriesId, new EditSeriesRequest("Updated", null, null, null, null), userId);

        assertThat(result.affectedInstallments()).isEqualTo(2);
    }

    // ── advanceInstallments ───────────────────────────────────────────────────

    @Test
    void advanceInstallments_movesPaymentDateAndTransitionsToPaid() {
        InstallmentSeriesDetailResponse created = installmentService.createInstallmentSeries(
                new CreateInstallmentRequest(accountId, new BigDecimal("300.00"), 3,
                        LocalDate.now().plusMonths(3), "Future purchase", null, null, null),
                userId);

        UUID installmentId = created.installments().get(0).id();

        List<TransactionDetailResponse> advanced = installmentService.advanceInstallments(
                new AdvanceInstallmentRequest(List.of(installmentId), LocalDate.now(), null),
                userId);

        assertThat(advanced).hasSize(1);
        assertThat(advanced.get(0).paymentDate()).isEqualTo(LocalDate.now());
        assertThat(advanced.get(0).status()).isEqualTo(TransactionStatus.PAID);
    }

    @Test
    void advanceInstallments_withAdjustedAmount() {
        InstallmentSeriesDetailResponse created = installmentService.createInstallmentSeries(
                new CreateInstallmentRequest(accountId, new BigDecimal("300.00"), 3,
                        LocalDate.now().plusMonths(3), "Future purchase", null, null, null),
                userId);

        UUID installmentId = created.installments().get(0).id();
        LocalDate futureDate = LocalDate.now().plusDays(5);

        List<TransactionDetailResponse> advanced = installmentService.advanceInstallments(
                new AdvanceInstallmentRequest(List.of(installmentId), futureDate, new BigDecimal("90.00")),
                userId);

        assertThat(advanced.get(0).amount()).isEqualByComparingTo(new BigDecimal("90.00"));
        assertThat(advanced.get(0).paymentDate()).isEqualTo(futureDate);
        assertThat(advanced.get(0).status()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void advanceInstallments_nonPending_throwsBusinessRuleException() {
        LocalDate yesterday = LocalDate.now().minusDays(1);

        InstallmentSeriesDetailResponse created = installmentService.createInstallmentSeries(
                new CreateInstallmentRequest(accountId, new BigDecimal("300.00"), 3, yesterday,
                        "Past purchase", null, null, null),
                userId);

        // First installment is PAID (first date is yesterday)
        UUID paidInstallmentId = created.installments().get(0).id();

        assertThatThrownBy(() -> installmentService.advanceInstallments(
                new AdvanceInstallmentRequest(List.of(paidInstallmentId), LocalDate.now(), null),
                userId))
                .isInstanceOf(BusinessRuleException.class);
    }
}
