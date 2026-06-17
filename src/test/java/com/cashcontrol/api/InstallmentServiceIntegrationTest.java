package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateCategoryRequest;
import com.cashcontrol.api.dto.request.CreateInstallmentRequest;
import com.cashcontrol.api.dto.request.EarlySettlementRequest;
import com.cashcontrol.api.dto.request.EditInstallmentRequest;
import com.cashcontrol.api.dto.request.EditSeriesRequest;
import com.cashcontrol.api.dto.response.CategoryResponse;
import com.cashcontrol.api.dto.response.InstallmentSeriesDetailResponse;
import com.cashcontrol.api.repository.InstallmentSeriesRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.CategoryService;
import com.cashcontrol.api.service.InstallmentService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
class InstallmentServiceIntegrationTest {

    @Autowired private InstallmentService installmentService;
    @Autowired private AccountService accountService;
    @Autowired private CategoryService categoryService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private InstallmentSeriesRepository installmentSeriesRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @PersistenceContext private EntityManager entityManager;

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
                "install-service-" + UUID.randomUUID() + "@example.com");

        accountId = accountService.createAccount(
                new CreateAccountRequest("Test Account", AccountType.CHECKING, "BRL", null, 0, null),
                userId).id();
    }

    @Test
    void createInstallmentSeries_fullPersistence() {
        LocalDate firstPayment = LocalDate.now().plusDays(1);
        InstallmentSeriesDetailResponse result = installmentService.createInstallmentSeries(
                new CreateInstallmentRequest(accountId, new BigDecimal("1200.00"), 3,
                        firstPayment, "Laptop purchase", null, null, null, null, null),
                userId);

        assertThat(result.series().id()).isNotNull();
        assertThat(result.installments()).hasSize(3);

        entityManager.flush();
        entityManager.clear();

        var installments = transactionRepository.findAllByInstallmentSeries_Id(result.series().id());
        assertThat(installments).hasSize(3);

        installments.sort((a, b) -> a.getInstallmentNumber() - b.getInstallmentNumber());
        assertThat(installments.get(0).getAmount()).isEqualByComparingTo("400.00");
        assertThat(installments.get(1).getAmount()).isEqualByComparingTo("400.00");
        assertThat(installments.get(2).getAmount()).isEqualByComparingTo("400.00");

        assertThat(installments.get(0).getInstallmentNumber()).isEqualTo(1);
        assertThat(installments.get(1).getInstallmentNumber()).isEqualTo(2);
        assertThat(installments.get(2).getInstallmentNumber()).isEqualTo(3);

        installments.forEach(t -> {
            assertThat(t.getInstallmentSeries().getId()).isEqualTo(result.series().id());
            assertThat(t.getTotalInstallments()).isEqualTo(3);
        });
    }

    @Test
    void createInstallmentSeries_amountRemainderOnLastInstallment() {
        LocalDate firstPayment = LocalDate.now().plusDays(1);
        InstallmentSeriesDetailResponse result = installmentService.createInstallmentSeries(
                new CreateInstallmentRequest(accountId, new BigDecimal("100.00"), 3,
                        firstPayment, "Remainder test", null, null, null, null, null),
                userId);

        entityManager.flush();
        entityManager.clear();

        var installments = transactionRepository.findAllByInstallmentSeries_Id(result.series().id());
        installments.sort((a, b) -> a.getInstallmentNumber() - b.getInstallmentNumber());

        // 100.00 / 3 = 33.33 (scale=2, DOWN); last = 100.00 - (33.33 * 2) = 33.34
        assertThat(installments.get(0).getAmount()).isEqualByComparingTo("33.33");
        assertThat(installments.get(1).getAmount()).isEqualByComparingTo("33.33");
        assertThat(installments.get(2).getAmount()).isEqualByComparingTo("33.34");
    }

    @Test
    void earlySettlement_fullAtomicity() {
        // firstPaymentDate = yesterday → installment 1 is PAID, 2 and 3 are PENDING
        LocalDate yesterday = LocalDate.now().minusDays(1);
        InstallmentSeriesDetailResponse created = installmentService.createInstallmentSeries(
                new CreateInstallmentRequest(accountId, new BigDecimal("300.00"), 3,
                        yesterday, "Early settlement series", null, null, null, null, null),
                userId);

        UUID seriesId = created.series().id();
        LocalDate settlementDate = LocalDate.now();

        installmentService.earlySettlement(seriesId,
                new EarlySettlementRequest(new BigDecimal("180.00"), settlementDate), userId);

        entityManager.flush();
        entityManager.clear();

        var allTransactions = transactionRepository.findAllByInstallmentSeries_Id(seriesId);
        var installments = allTransactions.stream()
                .filter(t -> !t.isEarlySettlement())
                .sorted((a, b) -> a.getInstallmentNumber() - b.getInstallmentNumber())
                .toList();
        var settlements = allTransactions.stream()
                .filter(t -> t.isEarlySettlement())
                .toList();

        assertThat(installments.get(0).getStatus()).isEqualTo(TransactionStatus.PAID);
        assertThat(installments.get(1).getStatus()).isEqualTo(TransactionStatus.CANCELLED);
        assertThat(installments.get(2).getStatus()).isEqualTo(TransactionStatus.CANCELLED);

        assertThat(settlements).hasSize(1);
        assertThat(settlements.get(0).getStatus()).isEqualTo(TransactionStatus.PAID);
        assertThat(settlements.get(0).getAmount()).isEqualByComparingTo("180.00");

        var reloadedSeries = installmentSeriesRepository.findByIdAndUserId(seriesId, userId).orElseThrow();
        assertThat(reloadedSeries.isSettled()).isTrue();
        assertThat(reloadedSeries.getSettledAt()).isNotNull();
    }

    @Test
    void editSeries_detachedInstallmentNotUpdated() {
        LocalDate firstPayment = LocalDate.now().plusDays(1);
        InstallmentSeriesDetailResponse created = installmentService.createInstallmentSeries(
                new CreateInstallmentRequest(accountId, new BigDecimal("300.00"), 3,
                        firstPayment, "Series for detach test", null, null, null, null, null),
                userId);

        UUID seriesId = created.series().id();

        // Create two categories
        CategoryResponse detachedCategory = categoryService.createCategory(
                new CreateCategoryRequest("Detached Category", null, null, null, 0), userId);
        CategoryResponse seriesCategory = categoryService.createCategory(
                new CreateCategoryRequest("Series Category", null, null, null, 1), userId);

        // Mark installment 2 as detached by editing it individually
        UUID installment2Id = created.installments().get(1).id();
        installmentService.editInstallment(installment2Id,
                new EditInstallmentRequest(null, null, null, null, detachedCategory.id(), null),
                userId);

        // Edit series with the series category — only non-detached PENDING installments should update
        installmentService.editSeries(seriesId,
                new EditSeriesRequest(null, null, seriesCategory.id(), null, null, null, null),
                userId);

        entityManager.flush();
        entityManager.clear();

        var allInstallments = transactionRepository.findAllByInstallmentSeries_Id(seriesId)
                .stream()
                .filter(t -> !t.isEarlySettlement())
                .sorted((a, b) -> a.getInstallmentNumber() - b.getInstallmentNumber())
                .toList();

        // Installments 1 and 3 should have the series category
        assertThat(allInstallments.get(0).getCategory().getId()).isEqualTo(seriesCategory.id());
        assertThat(allInstallments.get(2).getCategory().getId()).isEqualTo(seriesCategory.id());

        // Installment 2 (detached) should retain its individually-assigned category
        assertThat(allInstallments.get(1).getCategory().getId()).isEqualTo(detachedCategory.id());
    }
}
