package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ConflictException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.response.AccountResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.AccountService;
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
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
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
class AccountControllerTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @MockitoBean private AccountService accountService;

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

    // ── POST /api/v1/accounts ─────────────────────────────────────────────────

    @Test
    void createAccount_returns201() throws Exception {
        AccountResponse response = buildAccountResponse(UUID.randomUUID());
        when(accountService.createAccount(any(), eq(userId))).thenReturn(response);

        mockMvc.perform(post("/api/v1/accounts")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"My Account\",\"type\":\"CHECKING\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Test Account"))
                .andExpect(jsonPath("$.type").value("CHECKING"));
    }

    @Test
    void createAccount_missingName_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CHECKING\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createAccount_missingType_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"My Account\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createAccount_duplicateName_returns409() throws Exception {
        when(accountService.createAccount(any(), eq(userId)))
                .thenThrow(new ConflictException("Name already exists"));

        mockMvc.perform(post("/api/v1/accounts")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Existing\",\"type\":\"CHECKING\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void createAccount_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"My Account\",\"type\":\"CHECKING\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/v1/accounts ──────────────────────────────────────────────────

    @Test
    void listAccounts_returns200WithList() throws Exception {
        when(accountService.listAccounts(eq(userId), eq(false)))
                .thenReturn(List.of(buildAccountResponse(UUID.randomUUID())));

        mockMvc.perform(get("/api/v1/accounts")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Test Account"));
    }

    @Test
    void listAccounts_withIncludeArchived_returnsAll() throws Exception {
        when(accountService.listAccounts(eq(userId), eq(true)))
                .thenReturn(List.of(buildAccountResponse(UUID.randomUUID())));

        mockMvc.perform(get("/api/v1/accounts?includeArchived=true")
                        .with(user(principal)))
                .andExpect(status().isOk());
    }

    @Test
    void listAccounts_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/v1/accounts/{id} ─────────────────────────────────────────────

    @Test
    void getAccount_found_returns200() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(accountService.getAccount(eq(accountId), eq(userId)))
                .thenReturn(buildAccountResponse(accountId));

        mockMvc.perform(get("/api/v1/accounts/" + accountId)
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId.toString()));
    }

    @Test
    void getAccount_notFound_returns404() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(accountService.getAccount(eq(accountId), eq(userId)))
                .thenThrow(new ResourceNotFoundException("Not found"));

        mockMvc.perform(get("/api/v1/accounts/" + accountId)
                        .with(user(principal)))
                .andExpect(status().isNotFound());
    }

    // ── PUT /api/v1/accounts/{id} ─────────────────────────────────────────────

    @Test
    void editAccount_returns200() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(accountService.editAccount(eq(accountId), any(), eq(userId)))
                .thenReturn(buildAccountResponse(accountId));

        mockMvc.perform(put("/api/v1/accounts/" + accountId)
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated\",\"type\":\"SAVINGS\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void editAccount_missingName_returns400() throws Exception {
        UUID accountId = UUID.randomUUID();
        mockMvc.perform(put("/api/v1/accounts/" + accountId)
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SAVINGS\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void editAccount_conflictName_returns409() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(accountService.editAccount(eq(accountId), any(), eq(userId)))
                .thenThrow(new ConflictException("Name conflict"));

        mockMvc.perform(put("/api/v1/accounts/" + accountId)
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Existing\",\"type\":\"SAVINGS\"}"))
                .andExpect(status().isConflict());
    }

    // ── POST /api/v1/accounts/{id}/archive ────────────────────────────────────

    @Test
    void archiveAccount_returns200() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(accountService.archiveAccount(eq(accountId), eq(userId)))
                .thenReturn(buildAccountResponse(accountId));

        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/archive")
                        .with(user(principal)))
                .andExpect(status().isOk());
    }

    @Test
    void archiveAccount_alreadyArchived_returns422() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(accountService.archiveAccount(eq(accountId), eq(userId)))
                .thenThrow(new BusinessRuleException("Already archived"));

        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/archive")
                        .with(user(principal)))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── POST /api/v1/accounts/{id}/unarchive ──────────────────────────────────

    @Test
    void unarchiveAccount_returns200() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(accountService.unarchiveAccount(eq(accountId), eq(userId)))
                .thenReturn(buildAccountResponse(accountId));

        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/unarchive")
                        .with(user(principal)))
                .andExpect(status().isOk());
    }

    // ── DELETE /api/v1/accounts/{id} ──────────────────────────────────────────

    @Test
    void deleteAccount_returns204() throws Exception {
        UUID accountId = UUID.randomUUID();
        doNothing().when(accountService).deleteAccount(eq(accountId), eq(userId));

        mockMvc.perform(delete("/api/v1/accounts/" + accountId)
                        .with(user(principal)))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteAccount_hasTransactions_returns422() throws Exception {
        UUID accountId = UUID.randomUUID();
        doThrow(new BusinessRuleException("Has transactions"))
                .when(accountService).deleteAccount(eq(accountId), eq(userId));

        mockMvc.perform(delete("/api/v1/accounts/" + accountId)
                        .with(user(principal)))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── POST /api/v1/accounts/{id}/adjust ────────────────────────────────────

    @Test
    void manualAdjustment_returns200() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(accountService.manualAdjustment(eq(accountId), any(), eq(userId)))
                .thenReturn(buildAccountResponse(accountId));

        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/adjust")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"50.00\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void manualAdjustment_missingAmount_returns400() throws Exception {
        UUID accountId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/adjust")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ── POST /api/v1/accounts/transfers ──────────────────────────────────────

    @Test
    void createTransfer_returns204() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID destId = UUID.randomUUID();
        doNothing().when(accountService).createTransfer(any(), eq(userId));

        mockMvc.perform(post("/api/v1/accounts/transfers")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":\"" + sourceId + "\",\"destinationAccountId\":\"" + destId + "\",\"amount\":\"100.00\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void createTransfer_missingSourceId_returns400() throws Exception {
        UUID destId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/accounts/transfers")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destinationAccountId\":\"" + destId + "\",\"amount\":\"100.00\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createTransfer_zeroAmount_returns400() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID destId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/accounts/transfers")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":\"" + sourceId + "\",\"destinationAccountId\":\"" + destId + "\",\"amount\":\"0.00\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createTransfer_sameAccount_returns422() throws Exception {
        UUID accountId = UUID.randomUUID();
        doThrow(new BusinessRuleException("Same account"))
                .when(accountService).createTransfer(any(), eq(userId));

        mockMvc.perform(post("/api/v1/accounts/transfers")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":\"" + accountId + "\",\"destinationAccountId\":\"" + accountId + "\",\"amount\":\"100.00\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── DELETE /api/v1/accounts/transfers/{groupId} ───────────────────────────

    @Test
    void deleteTransfer_returns204() throws Exception {
        UUID groupId = UUID.randomUUID();
        doNothing().when(accountService).deleteTransfer(eq(groupId), eq(userId));

        mockMvc.perform(delete("/api/v1/accounts/transfers/" + groupId)
                        .with(user(principal)))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteTransfer_notFound_returns404() throws Exception {
        UUID groupId = UUID.randomUUID();
        doThrow(new ResourceNotFoundException("Not found"))
                .when(accountService).deleteTransfer(eq(groupId), eq(userId));

        mockMvc.perform(delete("/api/v1/accounts/transfers/" + groupId)
                        .with(user(principal)))
                .andExpect(status().isNotFound());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private AuthenticatedUser buildAuthenticatedUser(UUID id) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setEmail("controller-test-" + id + "@example.com");
        user.setAccountStatus(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_ACTIVE));
        user.setAuthOrigin(TestEntityFactory.authOrigin(UserSlugConstants.ORIGIN_LOCAL));
        user.setCredentialsUpdatedAt(Instant.now());
        return new AuthenticatedUser(user, createAuthorityList());
    }

    private AccountResponse buildAccountResponse(UUID id) {
        return new AccountResponse(id, "Test Account", AccountType.CHECKING, "BRL",
                null, 0, BigDecimal.ZERO, null, Instant.now(), Instant.now());
    }
}
