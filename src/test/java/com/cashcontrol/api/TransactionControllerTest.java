package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.response.AttachmentResponse;
import com.cashcontrol.api.dto.response.TransactionDetailResponse;
import com.cashcontrol.api.dto.response.TransactionSummaryResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.AttachmentService;
import com.cashcontrol.api.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import org.springframework.http.HttpMethod;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class TransactionControllerTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @MockitoBean private TransactionService transactionService;
    @MockitoBean private AttachmentService attachmentService;

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

    // ── POST /api/v1/transactions ─────────────────────────────────────────────

    @Test
    void createTransaction_returns201() throws Exception {
        UUID txId = UUID.randomUUID();
        when(transactionService.createTransaction(any(), eq(userId))).thenReturn(buildDetail(txId));

        mockMvc.perform(post("/api/v1/transactions")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + UUID.randomUUID() + "\",\"type\":\"INCOME\","
                                + "\"amount\":\"100.00\",\"description\":\"Salary\",\"competenceDate\":\"2025-01-01\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(txId.toString()))
                .andExpect(jsonPath("$.type").value("INCOME"));
    }

    @Test
    void createTransaction_missingAccountId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/transactions")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"INCOME\",\"amount\":\"100.00\","
                                + "\"description\":\"Salary\",\"competenceDate\":\"2025-01-01\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createTransaction_missingAmount_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/transactions")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + UUID.randomUUID() + "\",\"type\":\"INCOME\","
                                + "\"description\":\"Salary\",\"competenceDate\":\"2025-01-01\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createTransaction_zeroAmount_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/transactions")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + UUID.randomUUID() + "\",\"type\":\"INCOME\","
                                + "\"amount\":\"0.00\",\"description\":\"Salary\",\"competenceDate\":\"2025-01-01\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createTransaction_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + UUID.randomUUID() + "\",\"type\":\"INCOME\","
                                + "\"amount\":\"100.00\",\"description\":\"Salary\",\"competenceDate\":\"2025-01-01\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createTransaction_businessRuleViolation_returns422() throws Exception {
        when(transactionService.createTransaction(any(), eq(userId)))
                .thenThrow(new BusinessRuleException("Use the dedicated transfer endpoint"));

        mockMvc.perform(post("/api/v1/transactions")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + UUID.randomUUID() + "\",\"type\":\"TRANSFER\","
                                + "\"amount\":\"100.00\",\"description\":\"Transfer\",\"competenceDate\":\"2025-01-01\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createTransaction_accountNotFound_returns404() throws Exception {
        when(transactionService.createTransaction(any(), eq(userId)))
                .thenThrow(new ResourceNotFoundException("Account not found"));

        mockMvc.perform(post("/api/v1/transactions")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + UUID.randomUUID() + "\",\"type\":\"INCOME\","
                                + "\"amount\":\"100.00\",\"description\":\"Salary\",\"competenceDate\":\"2025-01-01\"}"))
                .andExpect(status().isNotFound());
    }

    // ── GET /api/v1/transactions ──────────────────────────────────────────────

    @Test
    void listTransactions_returns200() throws Exception {
        Page<TransactionSummaryResponse> page = new PageImpl<>(List.of(buildSummary(UUID.randomUUID())));
        when(transactionService.listTransactions(any(), eq(userId), any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/transactions")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].description").value("Test Transaction"));
    }

    @Test
    void listTransactions_withFilters_returns200() throws Exception {
        when(transactionService.listTransactions(any(), eq(userId), any()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/transactions?type=INCOME&status=PAID")
                        .with(user(principal)))
                .andExpect(status().isOk());
    }

    @Test
    void listTransactions_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/transactions"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/v1/transactions/{id} ─────────────────────────────────────────

    @Test
    void getTransaction_found_returns200() throws Exception {
        UUID txId = UUID.randomUUID();
        when(transactionService.getTransaction(eq(txId), eq(userId))).thenReturn(buildDetail(txId));

        mockMvc.perform(get("/api/v1/transactions/" + txId)
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(txId.toString()));
    }

    @Test
    void getTransaction_notFound_returns404() throws Exception {
        UUID txId = UUID.randomUUID();
        when(transactionService.getTransaction(eq(txId), eq(userId)))
                .thenThrow(new ResourceNotFoundException("Not found"));

        mockMvc.perform(get("/api/v1/transactions/" + txId)
                        .with(user(principal)))
                .andExpect(status().isNotFound());
    }

    @Test
    void getTransaction_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    // ── PUT /api/v1/transactions/{id} ─────────────────────────────────────────

    @Test
    void editTransaction_returns200() throws Exception {
        UUID txId = UUID.randomUUID();
        when(transactionService.editTransaction(eq(txId), any(), eq(userId))).thenReturn(buildDetail(txId));

        mockMvc.perform(put("/api/v1/transactions/" + txId)
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Updated\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void editTransaction_notFound_returns404() throws Exception {
        UUID txId = UUID.randomUUID();
        when(transactionService.editTransaction(eq(txId), any(), eq(userId)))
                .thenThrow(new ResourceNotFoundException("Not found"));

        mockMvc.perform(put("/api/v1/transactions/" + txId)
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Updated\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void editTransaction_cancelledTransaction_returns422() throws Exception {
        UUID txId = UUID.randomUUID();
        when(transactionService.editTransaction(eq(txId), any(), eq(userId)))
                .thenThrow(new BusinessRuleException("Cancelled transactions cannot be edited"));

        mockMvc.perform(put("/api/v1/transactions/" + txId)
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Updated\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── DELETE /api/v1/transactions/{id} ──────────────────────────────────────

    @Test
    void deleteTransaction_returns204() throws Exception {
        UUID txId = UUID.randomUUID();
        doNothing().when(transactionService).deleteTransaction(eq(txId), eq(userId));

        mockMvc.perform(delete("/api/v1/transactions/" + txId)
                        .with(user(principal)))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteTransaction_notFound_returns404() throws Exception {
        UUID txId = UUID.randomUUID();
        doThrow(new ResourceNotFoundException("Not found"))
                .when(transactionService).deleteTransaction(eq(txId), eq(userId));

        mockMvc.perform(delete("/api/v1/transactions/" + txId)
                        .with(user(principal)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteTransaction_transferLeg_returns422() throws Exception {
        UUID txId = UUID.randomUUID();
        doThrow(new BusinessRuleException("Transfer legs must be deleted as a pair"))
                .when(transactionService).deleteTransaction(eq(txId), eq(userId));

        mockMvc.perform(delete("/api/v1/transactions/" + txId)
                        .with(user(principal)))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── POST /api/v1/transactions/{id}/pay ───────────────────────────────────

    @Test
    void markAsPaid_returns200() throws Exception {
        UUID txId = UUID.randomUUID();
        when(transactionService.markAsPaid(eq(txId), any(), eq(userId))).thenReturn(buildDetail(txId));

        mockMvc.perform(post("/api/v1/transactions/" + txId + "/pay")
                        .with(user(principal)))
                .andExpect(status().isOk());
    }

    @Test
    void markAsPaid_withCustomDate_returns200() throws Exception {
        UUID txId = UUID.randomUUID();
        when(transactionService.markAsPaid(eq(txId), any(), eq(userId))).thenReturn(buildDetail(txId));

        mockMvc.perform(post("/api/v1/transactions/" + txId + "/pay")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentDate\":\"2025-01-15\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void markAsPaid_alreadyPaid_returns422() throws Exception {
        UUID txId = UUID.randomUUID();
        when(transactionService.markAsPaid(eq(txId), any(), eq(userId)))
                .thenThrow(new BusinessRuleException("Only PENDING or OVERDUE transactions can be marked as paid"));

        mockMvc.perform(post("/api/v1/transactions/" + txId + "/pay")
                        .with(user(principal)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void markAsPaid_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/transactions/" + UUID.randomUUID() + "/pay"))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /api/v1/transactions/{id}/cancel ─────────────────────────────────

    @Test
    void cancelTransaction_returns200() throws Exception {
        UUID txId = UUID.randomUUID();
        when(transactionService.cancelTransaction(eq(txId), eq(userId))).thenReturn(buildDetail(txId));

        mockMvc.perform(post("/api/v1/transactions/" + txId + "/cancel")
                        .with(user(principal)))
                .andExpect(status().isOk());
    }

    @Test
    void cancelTransaction_alreadyCancelled_returns422() throws Exception {
        UUID txId = UUID.randomUUID();
        when(transactionService.cancelTransaction(eq(txId), eq(userId)))
                .thenThrow(new BusinessRuleException("Transaction is already cancelled"));

        mockMvc.perform(post("/api/v1/transactions/" + txId + "/cancel")
                        .with(user(principal)))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── POST /api/v1/transactions/{id}/attachments ────────────────────────────

    @Test
    void uploadAttachments_returns201() throws Exception {
        UUID txId = UUID.randomUUID();
        AttachmentResponse resp = new AttachmentResponse(
                UUID.randomUUID(), "receipt.pdf", "application/pdf", 1024L, Instant.now());
        when(attachmentService.attach(eq(txId), any(), eq(userId))).thenReturn(List.of(resp));

        MockMultipartFile file = new MockMultipartFile(
                "files", "receipt.pdf", "application/pdf", new byte[]{1, 2, 3});

        mockMvc.perform(multipart(HttpMethod.POST, "/api/v1/transactions/" + txId + "/attachments")
                        .file(file)
                        .with(user(principal)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].originalFilename").value("receipt.pdf"));
    }

    @Test
    void uploadAttachments_unsupportedType_returns422() throws Exception {
        UUID txId = UUID.randomUUID();
        when(attachmentService.attach(eq(txId), any(), eq(userId)))
                .thenThrow(new BusinessRuleException("Unsupported file type"));

        MockMultipartFile file = new MockMultipartFile(
                "files", "script.exe", "application/octet-stream", new byte[]{1, 2, 3});

        mockMvc.perform(multipart(HttpMethod.POST, "/api/v1/transactions/" + txId + "/attachments")
                        .file(file)
                        .with(user(principal)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void uploadAttachments_unauthenticated_returns401() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "f.pdf", "application/pdf", new byte[]{1});

        mockMvc.perform(multipart("/api/v1/transactions/" + UUID.randomUUID() + "/attachments")
                        .file(file))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/v1/transactions/{id}/attachments ─────────────────────────────

    @Test
    void listAttachments_returns200() throws Exception {
        UUID txId = UUID.randomUUID();
        when(attachmentService.getAttachments(eq(txId), eq(userId))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/transactions/" + txId + "/attachments")
                        .with(user(principal)))
                .andExpect(status().isOk());
    }

    @Test
    void listAttachments_transactionNotFound_returns404() throws Exception {
        UUID txId = UUID.randomUUID();
        when(attachmentService.getAttachments(eq(txId), eq(userId)))
                .thenThrow(new ResourceNotFoundException("Transaction not found"));

        mockMvc.perform(get("/api/v1/transactions/" + txId + "/attachments")
                        .with(user(principal)))
                .andExpect(status().isNotFound());
    }

    // ── DELETE /api/v1/transactions/{id}/attachments/{attachmentId} ───────────

    @Test
    void deleteAttachment_returns204() throws Exception {
        UUID txId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        doNothing().when(attachmentService).deleteAttachment(eq(attachmentId), eq(userId));

        mockMvc.perform(delete("/api/v1/transactions/" + txId + "/attachments/" + attachmentId)
                        .with(user(principal)))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteAttachment_notFound_returns404() throws Exception {
        UUID txId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        doThrow(new ResourceNotFoundException("Attachment not found"))
                .when(attachmentService).deleteAttachment(eq(attachmentId), eq(userId));

        mockMvc.perform(delete("/api/v1/transactions/" + txId + "/attachments/" + attachmentId)
                        .with(user(principal)))
                .andExpect(status().isNotFound());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private AuthenticatedUser buildAuthenticatedUser(UUID id) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setEmail("tx-controller-" + id + "@example.com");
        user.setAccountStatus(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_ACTIVE));
        user.setAuthOrigin(TestEntityFactory.authOrigin(UserSlugConstants.ORIGIN_LOCAL));
        user.setCredentialsUpdatedAt(Instant.now());
        return new AuthenticatedUser(user, createAuthorityList());
    }

    private TransactionDetailResponse buildDetail(UUID id) {
        return new TransactionDetailResponse(
                id, UUID.randomUUID(), "Checking",
                TransactionType.INCOME, TransactionStatus.PAID,
                new BigDecimal("100.00"), "Test Transaction", null,
                LocalDate.now(), LocalDate.now(),
                null, null, null, null,
                Set.of(), null, null,
                null, null, null,
                false, null, Instant.now(), Instant.now(),
                null, null);
    }

    private TransactionSummaryResponse buildSummary(UUID id) {
        return new TransactionSummaryResponse(
                id, UUID.randomUUID(), "Checking",
                TransactionType.INCOME, TransactionStatus.PAID,
                new BigDecimal("100.00"), "Test Transaction",
                LocalDate.now(), LocalDate.now(),
                null, null, Instant.now(), null);
    }
}
