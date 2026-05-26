package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.response.EarlySettlementResponse;
import com.cashcontrol.api.dto.response.EditSeriesResult;
import com.cashcontrol.api.dto.response.InstallmentSeriesDetailResponse;
import com.cashcontrol.api.dto.response.InstallmentSeriesResponse;
import com.cashcontrol.api.dto.response.TransactionDetailResponse;
import com.cashcontrol.api.dto.response.TransactionSummaryResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.InstallmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class InstallmentControllerTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @MockitoBean private InstallmentService installmentService;

    private MockMvc mockMvc;
    private AuthenticatedUser principal;
    private UUID userId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        userId = UUID.randomUUID();
        principal = buildAuthenticatedUser(userId);
    }

    // ── POST /api/v1/installments ─────────────────────────────────────────────

    @Test
    void createInstallmentSeries_returns201() throws Exception {
        when(installmentService.createInstallmentSeries(any(), eq(userId)))
                .thenReturn(buildSeriesDetailResponse());

        mockMvc.perform(post("/api/v1/installments")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + UUID.randomUUID() + "\"," +
                                 "\"totalAmount\":\"300.00\"," +
                                 "\"totalInstallments\":3," +
                                 "\"firstPaymentDate\":\"2026-07-01\"," +
                                 "\"description\":\"Test purchase\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.series.description").value("Test purchase"));
    }

    @Test
    void createInstallmentSeries_missingAccountId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/installments")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"totalAmount\":\"300.00\",\"totalInstallments\":3," +
                                 "\"firstPaymentDate\":\"2026-07-01\",\"description\":\"Test\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createInstallmentSeries_missingDescription_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/installments")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + UUID.randomUUID() + "\"," +
                                 "\"totalAmount\":\"300.00\",\"totalInstallments\":3," +
                                 "\"firstPaymentDate\":\"2026-07-01\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createInstallmentSeries_installmentsLessThan2_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/installments")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + UUID.randomUUID() + "\"," +
                                 "\"totalAmount\":\"300.00\",\"totalInstallments\":1," +
                                 "\"firstPaymentDate\":\"2026-07-01\",\"description\":\"Test\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createInstallmentSeries_zeroAmount_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/installments")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + UUID.randomUUID() + "\"," +
                                 "\"totalAmount\":\"0.00\",\"totalInstallments\":3," +
                                 "\"firstPaymentDate\":\"2026-07-01\",\"description\":\"Test\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createInstallmentSeries_accountNotFound_returns404() throws Exception {
        when(installmentService.createInstallmentSeries(any(), eq(userId)))
                .thenThrow(new ResourceNotFoundException("Account not found"));

        mockMvc.perform(post("/api/v1/installments")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + UUID.randomUUID() + "\"," +
                                 "\"totalAmount\":\"300.00\",\"totalInstallments\":3," +
                                 "\"firstPaymentDate\":\"2026-07-01\",\"description\":\"Test\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createInstallmentSeries_archivedAccount_returns422() throws Exception {
        when(installmentService.createInstallmentSeries(any(), eq(userId)))
                .thenThrow(new BusinessRuleException("Account is archived"));

        mockMvc.perform(post("/api/v1/installments")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + UUID.randomUUID() + "\"," +
                                 "\"totalAmount\":\"300.00\",\"totalInstallments\":3," +
                                 "\"firstPaymentDate\":\"2026-07-01\",\"description\":\"Test\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createInstallmentSeries_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/installments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + UUID.randomUUID() + "\"," +
                                 "\"totalAmount\":\"300.00\",\"totalInstallments\":3," +
                                 "\"firstPaymentDate\":\"2026-07-01\",\"description\":\"Test\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ── PUT /api/v1/installments/series/{seriesId} ────────────────────────────

    @Test
    void editSeries_returns200() throws Exception {
        UUID seriesId = UUID.randomUUID();
        when(installmentService.editSeries(eq(seriesId), any(), eq(userId)))
                .thenReturn(new EditSeriesResult(buildSeriesResponse(seriesId), 3));

        mockMvc.perform(put("/api/v1/installments/series/" + seriesId)
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Updated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedInstallments").value(3));
    }

    @Test
    void editSeries_notFound_returns404() throws Exception {
        UUID seriesId = UUID.randomUUID();
        when(installmentService.editSeries(eq(seriesId), any(), eq(userId)))
                .thenThrow(new ResourceNotFoundException("Series not found"));

        mockMvc.perform(put("/api/v1/installments/series/" + seriesId)
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Updated\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void editSeries_alreadySettled_returns422() throws Exception {
        UUID seriesId = UUID.randomUUID();
        when(installmentService.editSeries(eq(seriesId), any(), eq(userId)))
                .thenThrow(new BusinessRuleException("Series is settled"));

        mockMvc.perform(put("/api/v1/installments/series/" + seriesId)
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Updated\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── PUT /api/v1/installments/{transactionId} ──────────────────────────────

    @Test
    void editInstallment_returns200() throws Exception {
        UUID txId = UUID.randomUUID();
        when(installmentService.editInstallment(eq(txId), any(), eq(userId)))
                .thenReturn(buildTransactionDetailResponse(txId));

        mockMvc.perform(put("/api/v1/installments/" + txId)
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Updated installment\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void editInstallment_notFound_returns404() throws Exception {
        UUID txId = UUID.randomUUID();
        when(installmentService.editInstallment(eq(txId), any(), eq(userId)))
                .thenThrow(new ResourceNotFoundException("Transaction not found"));

        mockMvc.perform(put("/api/v1/installments/" + txId)
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Updated\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void editInstallment_notAnInstallment_returns422() throws Exception {
        UUID txId = UUID.randomUUID();
        when(installmentService.editInstallment(eq(txId), any(), eq(userId)))
                .thenThrow(new BusinessRuleException("Not an installment"));

        mockMvc.perform(put("/api/v1/installments/" + txId)
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Updated\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── POST /api/v1/installments/series/{seriesId}/settle ────────────────────

    @Test
    void earlySettlement_returns200() throws Exception {
        UUID seriesId = UUID.randomUUID();
        when(installmentService.earlySettlement(eq(seriesId), any(), eq(userId)))
                .thenReturn(new EarlySettlementResponse(buildTransactionDetailResponse(UUID.randomUUID()), 3));

        mockMvc.perform(post("/api/v1/installments/series/" + seriesId + "/settle")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settlementAmount\":\"280.00\",\"settlementDate\":\"2026-06-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledInstallments").value(3));
    }

    @Test
    void earlySettlement_missingSettlementAmount_returns400() throws Exception {
        UUID seriesId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/installments/series/" + seriesId + "/settle")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settlementDate\":\"2026-06-01\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void earlySettlement_missingDate_returns400() throws Exception {
        UUID seriesId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/installments/series/" + seriesId + "/settle")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settlementAmount\":\"280.00\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void earlySettlement_alreadySettled_returns422() throws Exception {
        UUID seriesId = UUID.randomUUID();
        when(installmentService.earlySettlement(eq(seriesId), any(), eq(userId)))
                .thenThrow(new BusinessRuleException("Already settled"));

        mockMvc.perform(post("/api/v1/installments/series/" + seriesId + "/settle")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settlementAmount\":\"280.00\",\"settlementDate\":\"2026-06-01\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void earlySettlement_notFound_returns404() throws Exception {
        UUID seriesId = UUID.randomUUID();
        when(installmentService.earlySettlement(eq(seriesId), any(), eq(userId)))
                .thenThrow(new ResourceNotFoundException("Series not found"));

        mockMvc.perform(post("/api/v1/installments/series/" + seriesId + "/settle")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settlementAmount\":\"280.00\",\"settlementDate\":\"2026-06-01\"}"))
                .andExpect(status().isNotFound());
    }

    // ── POST /api/v1/installments/advance ─────────────────────────────────────

    @Test
    void advanceInstallments_returns200() throws Exception {
        UUID txId = UUID.randomUUID();
        when(installmentService.advanceInstallments(any(), eq(userId)))
                .thenReturn(List.of(buildTransactionDetailResponse(txId)));

        mockMvc.perform(post("/api/v1/installments/advance")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"installmentIds\":[\"" + txId + "\"],\"newPaymentDate\":\"2026-06-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(txId.toString()));
    }

    @Test
    void advanceInstallments_missingIds_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/installments/advance")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPaymentDate\":\"2026-06-01\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void advanceInstallments_missingDate_returns400() throws Exception {
        UUID txId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/installments/advance")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"installmentIds\":[\"" + txId + "\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void advanceInstallments_notPending_returns422() throws Exception {
        UUID txId = UUID.randomUUID();
        when(installmentService.advanceInstallments(any(), eq(userId)))
                .thenThrow(new BusinessRuleException("Installment is not PENDING"));

        mockMvc.perform(post("/api/v1/installments/advance")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"installmentIds\":[\"" + txId + "\"],\"newPaymentDate\":\"2026-06-01\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void advanceInstallments_unauthenticated_returns401() throws Exception {
        UUID txId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/installments/advance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"installmentIds\":[\"" + txId + "\"],\"newPaymentDate\":\"2026-06-01\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private AuthenticatedUser buildAuthenticatedUser(UUID id) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setEmail("installment-controller-" + id + "@example.com");
        user.setAccountStatus(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_ACTIVE));
        user.setAuthOrigin(TestEntityFactory.authOrigin(UserSlugConstants.ORIGIN_LOCAL));
        user.setCredentialsUpdatedAt(Instant.now());
        return new AuthenticatedUser(user, createAuthorityList());
    }

    private InstallmentSeriesDetailResponse buildSeriesDetailResponse() {
        UUID seriesId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        InstallmentSeriesResponse series = buildSeriesResponse(seriesId);

        List<TransactionSummaryResponse> installments = List.of(
                new TransactionSummaryResponse(UUID.randomUUID(), accountId, "Test Account",
                        TransactionType.EXPENSE, TransactionStatus.PENDING,
                        new BigDecimal("100.00"), "Test purchase",
                        LocalDate.of(2026, 7, 1), null, null, null, Instant.now()),
                new TransactionSummaryResponse(UUID.randomUUID(), accountId, "Test Account",
                        TransactionType.EXPENSE, TransactionStatus.PENDING,
                        new BigDecimal("100.00"), "Test purchase",
                        LocalDate.of(2026, 8, 1), null, null, null, Instant.now()),
                new TransactionSummaryResponse(UUID.randomUUID(), accountId, "Test Account",
                        TransactionType.EXPENSE, TransactionStatus.PENDING,
                        new BigDecimal("100.00"), "Test purchase",
                        LocalDate.of(2026, 9, 1), null, null, null, Instant.now())
        );

        return new InstallmentSeriesDetailResponse(series, installments);
    }

    private InstallmentSeriesResponse buildSeriesResponse(UUID seriesId) {
        return new InstallmentSeriesResponse(
                seriesId, UUID.randomUUID(), "Test Account", TransactionType.EXPENSE,
                "Test purchase", new BigDecimal("300.00"), 3,
                LocalDate.of(2026, 7, 1), null, null, false, null,
                Instant.now(), Instant.now());
    }

    private TransactionDetailResponse buildTransactionDetailResponse(UUID txId) {
        return new TransactionDetailResponse(
                txId, UUID.randomUUID(), "Test Account", TransactionType.EXPENSE,
                TransactionStatus.PENDING, new BigDecimal("100.00"), "Test",
                null, LocalDate.of(2026, 7, 1), null, null, null,
                null, null, Set.of(), null, null, UUID.randomUUID(), 1, 3,
                false, null, Instant.now(), Instant.now());
    }
}
