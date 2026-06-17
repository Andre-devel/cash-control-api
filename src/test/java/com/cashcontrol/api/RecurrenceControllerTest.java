package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.DeleteRecurrenceStrategy;
import com.cashcontrol.api.domain.entity.RecurrenceFrequency;
import com.cashcontrol.api.domain.entity.RecurrenceStatus;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.response.DeleteRecurrenceResult;
import com.cashcontrol.api.dto.response.EditRecurrenceResult;
import com.cashcontrol.api.dto.response.RecurrenceCreationResponse;
import com.cashcontrol.api.dto.response.RecurrenceRuleResponse;
import com.cashcontrol.api.dto.response.TransactionDetailResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.RecurrenceService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class RecurrenceControllerTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @MockitoBean private RecurrenceService recurrenceService;

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

    // ── POST /api/v1/recurrences ──────────────────────────────────────────────

    @Test
    void createRecurrence_returns201() throws Exception {
        when(recurrenceService.createRecurrence(any(), eq(userId)))
                .thenReturn(buildCreationResponse());

        mockMvc.perform(post("/api/v1/recurrences")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + UUID.randomUUID() + "\"," +
                                 "\"type\":\"EXPENSE\"," +
                                 "\"amount\":\"500.00\"," +
                                 "\"description\":\"Monthly rent\"," +
                                 "\"startDate\":\"2026-06-01\"," +
                                 "\"frequency\":\"MONTHLY\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rule.description").value("Monthly rent"));
    }

    @Test
    void createRecurrence_missingAccountId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/recurrences")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"EXPENSE\",\"amount\":\"500.00\"," +
                                 "\"description\":\"Rent\",\"startDate\":\"2026-06-01\"," +
                                 "\"frequency\":\"MONTHLY\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRecurrence_missingAmount_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/recurrences")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + UUID.randomUUID() + "\"," +
                                 "\"type\":\"EXPENSE\",\"description\":\"Rent\"," +
                                 "\"startDate\":\"2026-06-01\",\"frequency\":\"MONTHLY\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRecurrence_zeroAmount_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/recurrences")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + UUID.randomUUID() + "\"," +
                                 "\"type\":\"EXPENSE\",\"amount\":\"0.00\"," +
                                 "\"description\":\"Rent\",\"startDate\":\"2026-06-01\"," +
                                 "\"frequency\":\"MONTHLY\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRecurrence_accountNotFound_returns404() throws Exception {
        when(recurrenceService.createRecurrence(any(), eq(userId)))
                .thenThrow(new ResourceNotFoundException("Account not found"));

        mockMvc.perform(post("/api/v1/recurrences")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + UUID.randomUUID() + "\"," +
                                 "\"type\":\"EXPENSE\",\"amount\":\"500.00\"," +
                                 "\"description\":\"Rent\",\"startDate\":\"2026-06-01\"," +
                                 "\"frequency\":\"MONTHLY\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createRecurrence_archivedAccount_returns422() throws Exception {
        when(recurrenceService.createRecurrence(any(), eq(userId)))
                .thenThrow(new BusinessRuleException("Account is archived"));

        mockMvc.perform(post("/api/v1/recurrences")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + UUID.randomUUID() + "\"," +
                                 "\"type\":\"EXPENSE\",\"amount\":\"500.00\"," +
                                 "\"description\":\"Rent\",\"startDate\":\"2026-06-01\"," +
                                 "\"frequency\":\"MONTHLY\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createRecurrence_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/recurrences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + UUID.randomUUID() + "\"," +
                                 "\"type\":\"EXPENSE\",\"amount\":\"500.00\"," +
                                 "\"description\":\"Rent\",\"startDate\":\"2026-06-01\"," +
                                 "\"frequency\":\"MONTHLY\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/v1/recurrences ───────────────────────────────────────────────

    @Test
    void listRecurrences_returns200() throws Exception {
        UUID ruleId = UUID.randomUUID();
        when(recurrenceService.listRecurrences(eq(userId)))
                .thenReturn(List.of(buildRuleResponse(ruleId)));

        mockMvc.perform(get("/api/v1/recurrences")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ruleId.toString()));
    }

    @Test
    void listRecurrences_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/recurrences"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/v1/recurrences/{id} ──────────────────────────────────────────

    @Test
    void getRecurrence_returns200() throws Exception {
        UUID ruleId = UUID.randomUUID();
        when(recurrenceService.getRecurrence(eq(ruleId), eq(userId)))
                .thenReturn(buildRuleResponse(ruleId));

        mockMvc.perform(get("/api/v1/recurrences/" + ruleId)
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ruleId.toString()));
    }

    @Test
    void getRecurrence_notFound_returns404() throws Exception {
        UUID ruleId = UUID.randomUUID();
        when(recurrenceService.getRecurrence(eq(ruleId), eq(userId)))
                .thenThrow(new ResourceNotFoundException("Recurrence not found"));

        mockMvc.perform(get("/api/v1/recurrences/" + ruleId)
                        .with(user(principal)))
                .andExpect(status().isNotFound());
    }

    // ── PUT /api/v1/recurrences/{id} ──────────────────────────────────────────

    @Test
    void editSeries_returns200() throws Exception {
        UUID ruleId = UUID.randomUUID();
        when(recurrenceService.editSeries(eq(ruleId), any(), eq(userId)))
                .thenReturn(new EditRecurrenceResult(buildRuleResponse(ruleId), 5));

        mockMvc.perform(put("/api/v1/recurrences/" + ruleId)
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"600.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedInstances").value(5));
    }

    @Test
    void editSeries_notFound_returns404() throws Exception {
        UUID ruleId = UUID.randomUUID();
        when(recurrenceService.editSeries(eq(ruleId), any(), eq(userId)))
                .thenThrow(new ResourceNotFoundException("Not found"));

        mockMvc.perform(put("/api/v1/recurrences/" + ruleId)
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"600.00\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void editSeries_deleted_returns422() throws Exception {
        UUID ruleId = UUID.randomUUID();
        when(recurrenceService.editSeries(eq(ruleId), any(), eq(userId)))
                .thenThrow(new BusinessRuleException("Rule is deleted"));

        mockMvc.perform(put("/api/v1/recurrences/" + ruleId)
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"600.00\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── POST /api/v1/recurrences/{id}/pause ───────────────────────────────────

    @Test
    void pauseRecurrence_returns200() throws Exception {
        UUID ruleId = UUID.randomUUID();
        RecurrenceRuleResponse paused = buildRuleResponse(ruleId, RecurrenceStatus.PAUSED);
        when(recurrenceService.pauseRecurrence(eq(ruleId), any(), eq(userId)))
                .thenReturn(paused);

        mockMvc.perform(post("/api/v1/recurrences/" + ruleId + "/pause")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));
    }

    @Test
    void pauseRecurrence_notActive_returns422() throws Exception {
        UUID ruleId = UUID.randomUUID();
        when(recurrenceService.pauseRecurrence(eq(ruleId), any(), eq(userId)))
                .thenThrow(new BusinessRuleException("Not ACTIVE"));

        mockMvc.perform(post("/api/v1/recurrences/" + ruleId + "/pause")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void pauseRecurrence_noBody_returns200() throws Exception {
        UUID ruleId = UUID.randomUUID();
        RecurrenceRuleResponse paused = buildRuleResponse(ruleId, RecurrenceStatus.PAUSED);
        when(recurrenceService.pauseRecurrence(eq(ruleId), any(), eq(userId)))
                .thenReturn(paused);

        mockMvc.perform(post("/api/v1/recurrences/" + ruleId + "/pause")
                        .with(user(principal)))
                .andExpect(status().isOk());
    }

    // ── POST /api/v1/recurrences/{id}/resume ──────────────────────────────────

    @Test
    void resumeRecurrence_returns200() throws Exception {
        UUID ruleId = UUID.randomUUID();
        when(recurrenceService.resumeRecurrence(eq(ruleId), eq(userId)))
                .thenReturn(buildRuleResponse(ruleId));

        mockMvc.perform(post("/api/v1/recurrences/" + ruleId + "/resume")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void resumeRecurrence_notPaused_returns422() throws Exception {
        UUID ruleId = UUID.randomUUID();
        when(recurrenceService.resumeRecurrence(eq(ruleId), eq(userId)))
                .thenThrow(new BusinessRuleException("Not PAUSED"));

        mockMvc.perform(post("/api/v1/recurrences/" + ruleId + "/resume")
                        .with(user(principal)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void resumeRecurrence_notFound_returns404() throws Exception {
        UUID ruleId = UUID.randomUUID();
        when(recurrenceService.resumeRecurrence(eq(ruleId), eq(userId)))
                .thenThrow(new ResourceNotFoundException("Not found"));

        mockMvc.perform(post("/api/v1/recurrences/" + ruleId + "/resume")
                        .with(user(principal)))
                .andExpect(status().isNotFound());
    }

    // ── DELETE /api/v1/recurrences/{id} ───────────────────────────────────────

    @Test
    void deleteRecurrence_futureOnly_returns200() throws Exception {
        UUID ruleId = UUID.randomUUID();
        when(recurrenceService.deleteRecurrence(eq(ruleId), eq(DeleteRecurrenceStrategy.FUTURE_ONLY), eq(userId)))
                .thenReturn(new DeleteRecurrenceResult(3));

        mockMvc.perform(delete("/api/v1/recurrences/" + ruleId)
                        .with(user(principal))
                        .param("strategy", "FUTURE_ONLY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledInstances").value(3));
    }

    @Test
    void deleteRecurrence_all_returns200() throws Exception {
        UUID ruleId = UUID.randomUUID();
        when(recurrenceService.deleteRecurrence(eq(ruleId), eq(DeleteRecurrenceStrategy.ALL), eq(userId)))
                .thenReturn(new DeleteRecurrenceResult(7));

        mockMvc.perform(delete("/api/v1/recurrences/" + ruleId)
                        .with(user(principal))
                        .param("strategy", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledInstances").value(7));
    }

    @Test
    void deleteRecurrence_defaultStrategy_returns200() throws Exception {
        UUID ruleId = UUID.randomUUID();
        when(recurrenceService.deleteRecurrence(eq(ruleId), eq(DeleteRecurrenceStrategy.FUTURE_ONLY), eq(userId)))
                .thenReturn(new DeleteRecurrenceResult(2));

        mockMvc.perform(delete("/api/v1/recurrences/" + ruleId)
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledInstances").value(2));
    }

    @Test
    void deleteRecurrence_notFound_returns404() throws Exception {
        UUID ruleId = UUID.randomUUID();
        when(recurrenceService.deleteRecurrence(eq(ruleId), any(), eq(userId)))
                .thenThrow(new ResourceNotFoundException("Not found"));

        mockMvc.perform(delete("/api/v1/recurrences/" + ruleId)
                        .with(user(principal)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteRecurrence_unauthenticated_returns401() throws Exception {
        UUID ruleId = UUID.randomUUID();
        mockMvc.perform(delete("/api/v1/recurrences/" + ruleId))
                .andExpect(status().isUnauthorized());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private AuthenticatedUser buildAuthenticatedUser(UUID id) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setEmail("recurrence-ctrl-" + id + "@example.com");
        user.setAccountStatus(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_ACTIVE));
        user.setAuthOrigin(TestEntityFactory.authOrigin(UserSlugConstants.ORIGIN_LOCAL));
        user.setCredentialsUpdatedAt(Instant.now());
        return new AuthenticatedUser(user, createAuthorityList());
    }

    private RecurrenceCreationResponse buildCreationResponse() {
        UUID ruleId = UUID.randomUUID();
        return new RecurrenceCreationResponse(buildRuleResponse(ruleId), buildTransactionDetail());
    }

    private RecurrenceRuleResponse buildRuleResponse(UUID ruleId) {
        return buildRuleResponse(ruleId, RecurrenceStatus.ACTIVE);
    }

    private RecurrenceRuleResponse buildRuleResponse(UUID ruleId, RecurrenceStatus status) {
        return new RecurrenceRuleResponse(
                ruleId, UUID.randomUUID(), "Test Account",
                TransactionType.EXPENSE, RecurrenceFrequency.MONTHLY, status,
                new BigDecimal("500.00"), "Monthly rent",
                null, null, null, null,
                LocalDate.of(2026, 6, 1), null,
                LocalDate.of(2026, 7, 1),
                null, null,
                Instant.now(), Instant.now()
        );
    }

    private TransactionDetailResponse buildTransactionDetail() {
        return new TransactionDetailResponse(
                UUID.randomUUID(), UUID.randomUUID(), "Test Account",
                TransactionType.EXPENSE, TransactionStatus.PENDING,
                new BigDecimal("500.00"), "Monthly rent",
                null, LocalDate.of(2026, 6, 1), null,
                null, null, null, null,
                Set.of(), null, null, null, null, null,
                false, null, Instant.now(), Instant.now(),
                null, null
        );
    }
}
