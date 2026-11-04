package com.cashcontrol.api;

/**
 * @author Andre
 * 04 de Novembro. de 2026
 */

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.FixedIncomeKind;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.FixedIncomeHoldingRequest;
import com.cashcontrol.api.security.JwtService;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.investment.InvestmentService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
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
class SavingsGoalIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtService jwtService;
    @Autowired private AccountService accountService;
    @Autowired private InvestmentService investmentService;

    private MockMvc mockMvc;
    private UUID userId;
    private String token;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        userId = createUser();
        token = bearer(userId);
    }

    @Test
    void crud_withoutLink_currentIsZero_monthlyNeededNullWithoutDate_deleteWithoutProgressRemoves() throws Exception {
        String id = createGoal("{\"name\":\"Viagem\",\"targetAmount\":5000,\"icon\":\"plane\",\"color\":\"#00AAFF\"}");

        perform(get("/api/v1/goals/" + id), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Viagem"))
                .andExpect(jsonPath("$.targetAmount").value(5000))
                .andExpect(jsonPath("$.icon").value("plane"))
                .andExpect(jsonPath("$.color").value("#00AAFF"))
                .andExpect(jsonPath("$.current").value(0))
                .andExpect(jsonPath("$.percent").value(0))
                .andExpect(jsonPath("$.monthlyNeeded").value(nullValue()))
                .andExpect(jsonPath("$.archivedAt").value(nullValue()));

        perform(put("/api/v1/goals/" + id).content("{\"name\":\"Viagem ao Chile\",\"targetAmount\":8000}"), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Viagem ao Chile"))
                .andExpect(jsonPath("$.targetAmount").value(8000))
                .andExpect(jsonPath("$.icon").value(nullValue()));

        perform(get("/api/v1/goals"), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id));

        perform(delete("/api/v1/goals/" + id), token).andExpect(status().isNoContent());
        perform(get("/api/v1/goals/" + id), token).andExpect(status().isNotFound());
        perform(get("/api/v1/goals").param("includeArchived", "true"), token)
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void invalidRequest_is400() throws Exception {
        perform(post("/api/v1/goals").content("{\"name\":\"\",\"targetAmount\":0}"), token)
                .andExpect(status().isBadRequest());
    }

    @Test
    void progressFromAccount_monthlyNeededSplitsTheRemainderByMonthsLeft_deleteWithProgressArchives() throws Exception {
        UUID accountId = account("Poupança", AccountType.SAVINGS, "2000.00", userId);
        LocalDate targetDate = LocalDate.now().plusMonths(4);
        String id = createGoal("{\"name\":\"Reserva\",\"targetAmount\":10000,\"targetDate\":\"" + targetDate
                + "\",\"accountId\":\"" + accountId + "\"}");

        perform(get("/api/v1/goals/" + id), token)
                .andExpect(jsonPath("$.accountId").value(accountId.toString()))
                .andExpect(jsonPath("$.holdingId").value(nullValue()))
                .andExpect(jsonPath("$.current").value(2000.00))
                .andExpect(jsonPath("$.percent").value(20.00))
                .andExpect(jsonPath("$.monthlyNeeded").value(2000.00));

        perform(delete("/api/v1/goals/" + id), token).andExpect(status().isNoContent());
        perform(get("/api/v1/goals/" + id), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").value(notNullValue()));
        perform(get("/api/v1/goals"), token).andExpect(jsonPath("$.length()").value(0));
        perform(get("/api/v1/goals").param("includeArchived", "true"), token)
                .andExpect(jsonPath("$.length()").value(1));

        perform(post("/api/v1/goals/" + id + "/unarchive"), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").value(nullValue()));
        perform(delete("/api/v1/goals/" + id), token).andExpect(status().isNoContent());

        // Apagar de novo a meta arquivada remove de vez.
        perform(delete("/api/v1/goals/" + id), token).andExpect(status().isNoContent());
        perform(get("/api/v1/goals/" + id), token).andExpect(status().isNotFound());
    }

    @Test
    void progressFromHolding_usesNetValue_monthlyNeededIsZeroOnceReached() throws Exception {
        UUID investmentAccount = account("Corretora", AccountType.INVESTMENT, "0", userId);
        UUID holdingId = holding(investmentAccount, "3000.00", userId);

        String reached = createGoal("{\"name\":\"Carro\",\"targetAmount\":3000,\"targetDate\":\""
                + LocalDate.now().plusMonths(6) + "\",\"holdingId\":\"" + holdingId + "\"}");
        perform(get("/api/v1/goals/" + reached), token)
                .andExpect(jsonPath("$.holdingId").value(holdingId.toString()))
                .andExpect(jsonPath("$.current").value(3000.00))
                .andExpect(jsonPath("$.percent").value(100.00))
                .andExpect(jsonPath("$.monthlyNeeded").value(0));

        String pending = createGoal("{\"name\":\"Casa\",\"targetAmount\":6000,\"targetDate\":\""
                + LocalDate.now().plusMonths(3) + "\",\"holdingId\":\"" + holdingId + "\"}");
        perform(get("/api/v1/goals"), token)
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].id").value(pending))
                .andExpect(jsonPath("$[1].current").value(3000.00))
                .andExpect(jsonPath("$[1].percent").value(50.00))
                .andExpect(jsonPath("$[1].monthlyNeeded").value(1000.00));
    }

    @Test
    void accountOrHoldingOfAnotherUser_is404_andGoalOfAnotherUser_is404() throws Exception {
        UUID otherUser = createUser();
        UUID otherAccount = account("Conta alheia", AccountType.CHECKING, "100.00", otherUser);
        UUID otherHolding = holding(account("Corretora alheia", AccountType.INVESTMENT, "0", otherUser), "500.00", otherUser);

        perform(post("/api/v1/goals").content("{\"name\":\"Meta\",\"targetAmount\":100,\"accountId\":\""
                + otherAccount + "\"}"), token)
                .andExpect(status().isNotFound());
        perform(post("/api/v1/goals").content("{\"name\":\"Meta\",\"targetAmount\":100,\"holdingId\":\""
                + otherHolding + "\"}"), token)
                .andExpect(status().isNotFound());

        String id = createGoal("{\"name\":\"Minha meta\",\"targetAmount\":100}");
        perform(put("/api/v1/goals/" + id).content("{\"name\":\"Meta\",\"targetAmount\":100,\"accountId\":\""
                + otherAccount + "\"}"), token)
                .andExpect(status().isNotFound());

        String otherToken = bearer(otherUser);
        perform(get("/api/v1/goals/" + id), otherToken).andExpect(status().isNotFound());
        perform(delete("/api/v1/goals/" + id), otherToken).andExpect(status().isNotFound());
        perform(get("/api/v1/goals"), otherToken).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void linkingAccountAndHoldingTogether_is422() throws Exception {
        UUID accountId = account("Conta", AccountType.CHECKING, "100.00", userId);
        UUID holdingId = holding(account("Corretora", AccountType.INVESTMENT, "0", userId), "500.00", userId);

        perform(post("/api/v1/goals").content("{\"name\":\"Meta\",\"targetAmount\":100,\"accountId\":\""
                + accountId + "\",\"holdingId\":\"" + holdingId + "\"}"), token)
                .andExpect(status().isUnprocessableEntity());
    }

    private String createGoal(String body) throws Exception {
        String response = perform(post("/api/v1/goals").content(body), token)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private UUID account(String name, AccountType type, String initialBalance, UUID owner) {
        return accountService.createAccount(
                new CreateAccountRequest(name + " " + UUID.randomUUID(), type, "BRL", null, null,
                        new BigDecimal(initialBalance)), owner).id();
    }

    private UUID holding(UUID accountId, String principal, UUID owner) {
        return investmentService.createHolding(new FixedIncomeHoldingRequest(accountId, FixedIncomeKind.OTHER,
                null, null, "Fundo", null, true, null, LocalDate.now().minusDays(10), null,
                new BigDecimal(principal), null, null, null, null, null), owner, LocalDate.now()).id();
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String bearer) throws Exception {
        return mockMvc.perform(request.header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON));
    }

    private String bearer(UUID user) {
        return "Bearer " + jwtService.generateToken(user, List.of(), Instant.now());
    }

    private UUID createUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) "
                        + "VALUES (?, (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), "
                        + "(SELECT id FROM auth_origins WHERE slug = 'LOCAL'), NOW() - INTERVAL '1 minute') RETURNING id",
                UUID.class, "goal-" + UUID.randomUUID() + "@example.com");
    }
}
