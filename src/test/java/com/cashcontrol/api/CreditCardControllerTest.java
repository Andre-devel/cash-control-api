package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.CardBrand;
import com.cashcontrol.api.domain.entity.InvoiceStatus;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ConflictException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.response.CreditCardResponse;
import com.cashcontrol.api.dto.response.InvoiceItemResponse;
import com.cashcontrol.api.dto.response.InvoiceResponse;
import com.cashcontrol.api.dto.response.LimitUsageResponse;
import com.cashcontrol.api.dto.response.SpendingByCategoryResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.CreditCardService;
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
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class CreditCardControllerTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @MockitoBean private CreditCardService creditCardService;

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

    // ── POST /api/v1/cards ────────────────────────────────────────────────────

    @Test
    void createCard_returns201() throws Exception {
        CreditCardResponse response = buildCardResponse(UUID.randomUUID());
        when(creditCardService.createCard(any(), eq(userId))).thenReturn(response);

        mockMvc.perform(post("/api/v1/cards")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"My Visa\",\"brand\":\"VISA\",\"creditLimit\":\"5000.00\",\"closingDay\":15,\"dueDay\":10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("My Visa"));
    }

    @Test
    void createCard_missingBrand_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/cards")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"My Card\",\"creditLimit\":\"5000.00\",\"closingDay\":15,\"dueDay\":10}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createCard_missingName_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/cards")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"brand\":\"VISA\",\"creditLimit\":\"5000.00\",\"closingDay\":15,\"dueDay\":10}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createCard_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"My Visa\",\"brand\":\"VISA\",\"creditLimit\":\"5000.00\",\"closingDay\":15,\"dueDay\":10}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createCard_duplicateName_returns409() throws Exception {
        when(creditCardService.createCard(any(), eq(userId)))
                .thenThrow(new ConflictException("Card name already exists"));

        mockMvc.perform(post("/api/v1/cards")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Dup\",\"brand\":\"VISA\",\"creditLimit\":\"5000.00\",\"closingDay\":15,\"dueDay\":10}"))
                .andExpect(status().isConflict());
    }

    // ── GET /api/v1/cards ────────────────────────────────────────────────────

    @Test
    void listCards_returns200() throws Exception {
        when(creditCardService.listCards(eq(userId)))
                .thenReturn(List.of(buildCardResponse(UUID.randomUUID())));

        mockMvc.perform(get("/api/v1/cards")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void listCards_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/cards"))
                .andExpect(status().isUnauthorized());
    }

    // ── PUT /api/v1/cards/{id} ────────────────────────────────────────────────

    @Test
    void editCard_returns200() throws Exception {
        UUID cardId = UUID.randomUUID();
        when(creditCardService.editCard(eq(cardId), any(), eq(userId)))
                .thenReturn(buildCardResponse(cardId));

        mockMvc.perform(put("/api/v1/cards/" + cardId)
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated\",\"brand\":\"MASTERCARD\",\"creditLimit\":\"8000.00\",\"closingDay\":20,\"dueDay\":5}"))
                .andExpect(status().isOk());
    }

    @Test
    void editCard_notFound_returns404() throws Exception {
        UUID cardId = UUID.randomUUID();
        when(creditCardService.editCard(eq(cardId), any(), eq(userId)))
                .thenThrow(new ResourceNotFoundException("Not found"));

        mockMvc.perform(put("/api/v1/cards/" + cardId)
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated\",\"brand\":\"MASTERCARD\",\"creditLimit\":\"8000.00\",\"closingDay\":20,\"dueDay\":5}"))
                .andExpect(status().isNotFound());
    }

    // ── POST /api/v1/cards/{id}/archive ──────────────────────────────────────

    @Test
    void archiveCard_returns200() throws Exception {
        UUID cardId = UUID.randomUUID();
        CreditCardResponse archived = buildCardResponse(cardId);
        when(creditCardService.archiveCard(eq(cardId), eq(userId))).thenReturn(archived);

        mockMvc.perform(post("/api/v1/cards/" + cardId + "/archive")
                        .with(user(principal)))
                .andExpect(status().isOk());
    }

    @Test
    void archiveCard_alreadyArchived_returns422() throws Exception {
        UUID cardId = UUID.randomUUID();
        when(creditCardService.archiveCard(eq(cardId), eq(userId)))
                .thenThrow(new BusinessRuleException("Already archived"));

        mockMvc.perform(post("/api/v1/cards/" + cardId + "/archive")
                        .with(user(principal)))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── POST /api/v1/cards/{id}/charges ──────────────────────────────────────

    @Test
    void recordCharge_returns201() throws Exception {
        UUID cardId = UUID.randomUUID();
        InvoiceItemResponse itemResponse = buildItemResponse(UUID.randomUUID());
        when(creditCardService.recordCharge(eq(cardId), any(), eq(userId))).thenReturn(itemResponse);

        mockMvc.perform(post("/api/v1/cards/" + cardId + "/charges")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Supermarket\",\"amount\":\"150.00\",\"competenceDate\":\"2025-05-10\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void recordCharge_missingDescription_returns400() throws Exception {
        UUID cardId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/cards/" + cardId + "/charges")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"150.00\",\"competenceDate\":\"2025-05-10\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recordCharge_archivedCard_returns422() throws Exception {
        UUID cardId = UUID.randomUUID();
        when(creditCardService.recordCharge(eq(cardId), any(), eq(userId)))
                .thenThrow(new BusinessRuleException("Archived card"));

        mockMvc.perform(post("/api/v1/cards/" + cardId + "/charges")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Test\",\"amount\":\"100.00\",\"competenceDate\":\"2025-05-10\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── GET /api/v1/cards/{id}/invoices/{referenceMonth} ─────────────────────

    @Test
    void getInvoice_returns200() throws Exception {
        UUID cardId = UUID.randomUUID();
        InvoiceResponse invoiceResponse = buildInvoiceResponse(UUID.randomUUID(), cardId);
        when(creditCardService.getInvoice(eq(cardId), eq("2025-05"), eq(userId), eq(0), eq(20)))
                .thenReturn(invoiceResponse);

        mockMvc.perform(get("/api/v1/cards/" + cardId + "/invoices/2025-05")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceMonth").value("2025-05"));
    }

    @Test
    void getInvoice_notFound_returns404() throws Exception {
        UUID cardId = UUID.randomUUID();
        when(creditCardService.getInvoice(eq(cardId), eq("2025-01"), eq(userId), eq(0), eq(20)))
                .thenThrow(new ResourceNotFoundException("Not found"));

        mockMvc.perform(get("/api/v1/cards/" + cardId + "/invoices/2025-01")
                        .with(user(principal)))
                .andExpect(status().isNotFound());
    }

    // ── POST /api/v1/cards/invoices/{invoiceId}/pay ───────────────────────────

    @Test
    void payInvoice_returns200() throws Exception {
        UUID invoiceId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        InvoiceResponse invoiceResponse = buildInvoiceResponse(invoiceId, cardId);
        when(creditCardService.payInvoice(eq(invoiceId), any(), eq(userId))).thenReturn(invoiceResponse);

        mockMvc.perform(post("/api/v1/cards/invoices/" + invoiceId + "/pay")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"500.00\",\"sourceAccountId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void payInvoice_missingAmount_returns400() throws Exception {
        UUID invoiceId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/cards/invoices/" + invoiceId + "/pay")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void payInvoice_openInvoice_returns422() throws Exception {
        UUID invoiceId = UUID.randomUUID();
        when(creditCardService.payInvoice(eq(invoiceId), any(), eq(userId)))
                .thenThrow(new BusinessRuleException("Cannot pay an OPEN invoice"));

        mockMvc.perform(post("/api/v1/cards/invoices/" + invoiceId + "/pay")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"100.00\",\"sourceAccountId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── GET /api/v1/cards/{id}/limit ─────────────────────────────────────────

    @Test
    void getLimitUsage_returns200() throws Exception {
        UUID cardId = UUID.randomUUID();
        LimitUsageResponse limitResponse = new LimitUsageResponse(
                cardId, new BigDecimal("5000.00"), new BigDecimal("300.00"), new BigDecimal("4700.00"));
        when(creditCardService.getLimitUsage(eq(cardId), eq(userId))).thenReturn(limitResponse);

        mockMvc.perform(get("/api/v1/cards/" + cardId + "/limit")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditLimit").value(5000.00))
                .andExpect(jsonPath("$.usedLimit").value(300.00))
                .andExpect(jsonPath("$.availableLimit").value(4700.00));
    }

    @Test
    void getLimitUsage_notFound_returns404() throws Exception {
        UUID cardId = UUID.randomUUID();
        when(creditCardService.getLimitUsage(eq(cardId), eq(userId)))
                .thenThrow(new ResourceNotFoundException("Card not found"));

        mockMvc.perform(get("/api/v1/cards/" + cardId + "/limit")
                        .with(user(principal)))
                .andExpect(status().isNotFound());
    }

    // ── GET /api/v1/cards/{id}/spending ──────────────────────────────────────

    @Test
    void getSpendingByCategory_returns200() throws Exception {
        UUID cardId = UUID.randomUUID();
        when(creditCardService.getSpendingByCategory(eq(cardId), any(), any(), eq(userId)))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/v1/cards/" + cardId + "/spending")
                        .with(user(principal)))
                .andExpect(status().isOk());
    }

    @Test
    void getSpendingByCategory_withDateRange_returns200() throws Exception {
        UUID cardId = UUID.randomUUID();
        SpendingByCategoryResponse spending = new SpendingByCategoryResponse(
                UUID.randomUUID(), "Food", new BigDecimal("250.00"), new BigDecimal("50.00"));
        when(creditCardService.getSpendingByCategory(eq(cardId), eq(LocalDate.of(2025, 1, 1)),
                eq(LocalDate.of(2025, 3, 31)), eq(userId)))
                .thenReturn(List.of(spending));

        mockMvc.perform(get("/api/v1/cards/" + cardId + "/spending?from=2025-01-01&to=2025-03-31")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].categoryName").value("Food"));
    }

    @Test
    void getSpendingByCategory_unauthenticated_returns401() throws Exception {
        UUID cardId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/cards/" + cardId + "/spending"))
                .andExpect(status().isUnauthorized());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private AuthenticatedUser buildAuthenticatedUser(UUID id) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setEmail("cc-controller-test-" + id + "@example.com");
        user.setAccountStatus(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_ACTIVE));
        user.setAuthOrigin(TestEntityFactory.authOrigin(UserSlugConstants.ORIGIN_LOCAL));
        user.setCredentialsUpdatedAt(Instant.now());
        return new AuthenticatedUser(user, createAuthorityList());
    }

    private CreditCardResponse buildCardResponse(UUID id) {
        return new CreditCardResponse(id, "My Visa", CardBrand.VISA, "Test Bank", null,
                new BigDecimal("5000.00"), 15, 10, null, null, Instant.now(), Instant.now());
    }

    private InvoiceItemResponse buildItemResponse(UUID id) {
        return new InvoiceItemResponse(id, "Supermarket", new BigDecimal("150.00"),
                LocalDate.of(2025, 5, 10), null, null, null, null, null,
                false, null, null, null, null, Instant.now(), Instant.now());
    }

    private InvoiceResponse buildInvoiceResponse(UUID invoiceId, UUID cardId) {
        return new InvoiceResponse(invoiceId, cardId, "2025-05", InvoiceStatus.OPEN,
                LocalDate.of(2025, 5, 15), LocalDate.of(2025, 6, 10),
                BigDecimal.ZERO, BigDecimal.ZERO, 0, 20, 0,
                Collections.emptyList(), Instant.now(), Instant.now());
    }
}
