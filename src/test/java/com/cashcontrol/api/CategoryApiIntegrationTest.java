package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateCategoryRequest;
import com.cashcontrol.api.dto.request.CreateTransactionRequest;
import com.cashcontrol.api.dto.response.CategoryResponse;
import com.cashcontrol.api.security.JwtService;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.CategoryService;
import com.cashcontrol.api.service.TransactionService;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class CategoryApiIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtService jwtService;
    @Autowired private AccountService accountService;
    @Autowired private CategoryService categoryService;
    @Autowired private TransactionService transactionService;

    private MockMvc mockMvc;
    private UUID userId;
    private String token;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) " +
                "VALUES (?, " +
                "  (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), " +
                "  (SELECT id FROM auth_origins WHERE slug = 'LOCAL'), " +
                "  NOW() - INTERVAL '1 minute') " +
                "RETURNING id",
                UUID.class,
                "cat-api-" + UUID.randomUUID() + "@example.com");

        token = jwtService.generateToken(userId, List.of(), Instant.now());
    }

    @Test
    void listCategories_includesSystemCategories() throws Exception {
        mockMvc.perform(get("/api/v1/categories")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].isDefault").value(true));
    }

    @Test
    void createCategory_returns201() throws Exception {
        String body = """
                {
                    "name": "My Custom Category",
                    "sortOrder": 0
                }
                """;

        mockMvc.perform(post("/api/v1/categories")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("My Custom Category"))
                .andExpect(jsonPath("$.isDefault").value(false));
    }

    @Test
    void createCategory_thirdLevelNesting_returns422() throws Exception {
        CategoryResponse parent = categoryService.createCategory(
                new CreateCategoryRequest("Parent", null, null, null, 0), userId);
        CategoryResponse child = categoryService.createCategory(
                new CreateCategoryRequest("Child", parent.id(), null, null, 0), userId);

        String deepNestBody = """
                {
                    "name": "Grand Child",
                    "parentId": "%s",
                    "sortOrder": 0
                }
                """.formatted(child.id());

        mockMvc.perform(post("/api/v1/categories")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deepNestBody))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void archiveCategory_cascadesToSubcategories() throws Exception {
        CategoryResponse parent = categoryService.createCategory(
                new CreateCategoryRequest("ArchiveParent", null, null, null, 0), userId);
        categoryService.createCategory(
                new CreateCategoryRequest("ArchiveChild1", parent.id(), null, null, 0), userId);
        categoryService.createCategory(
                new CreateCategoryRequest("ArchiveChild2", parent.id(), null, null, 0), userId);

        mockMvc.perform(post("/api/v1/categories/{id}/archive", parent.id())
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isArchived").value(true));

        String listResponse = mockMvc.perform(get("/api/v1/categories")
                        .header("Authorization", bearer())
                        .param("includeArchived", "true"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String parentIdStr = parent.id().toString();
        java.util.List<java.util.Map<String, Object>> matched =
                JsonPath.read(listResponse, "$[?(@.id == '" + parentIdStr + "')]");
        java.util.Map<String, Object> archivedParent = matched.get(0);

        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> subcats =
                (java.util.List<java.util.Map<String, Object>>) archivedParent.get("subcategories");

        long archivedChildren = subcats.stream()
                .filter(c -> Boolean.TRUE.equals(c.get("isArchived")))
                .count();

        org.assertj.core.api.Assertions.assertThat(archivedParent.get("isArchived")).isEqualTo(true);
        org.assertj.core.api.Assertions.assertThat(archivedChildren).isEqualTo(2);
    }

    @Test
    void suggestCategory_returnsNonEmptyList() throws Exception {
        UUID accountId = accountService.createAccount(
                new CreateAccountRequest("Test Account", AccountType.CHECKING, "BRL", null, 0, null), userId).id();
        CategoryResponse category = categoryService.createCategory(
                new CreateCategoryRequest("Alimentação", null, null, null, 0), userId);

        for (int i = 0; i < 3; i++) {
            transactionService.createTransaction(new CreateTransactionRequest(
                    accountId, TransactionType.EXPENSE, new BigDecimal("50.00"), "Supermercado compras",
                    LocalDate.now(), LocalDate.now(), null, category.id(), null, null, null, TransactionStatus.PAID),
                    userId);
        }

        mockMvc.perform(get("/api/v1/categories/suggest")
                        .header("Authorization", bearer())
                        .param("description", "Supermercado"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void createAndListCategoryRules_works() throws Exception {
        CategoryResponse category = categoryService.createCategory(
                new CreateCategoryRequest("Saúde", null, null, null, 0), userId);

        String ruleBody = """
                {
                    "pattern": "farmácia",
                    "categoryId": "%s",
                    "priority": 1
                }
                """.formatted(category.id());

        String ruleResponse = mockMvc.perform(post("/api/v1/categories/rules")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ruleBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.pattern").value("farmácia"))
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(get("/api/v1/categories/rules")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].pattern").value("farmácia"));
    }

    @Test
    void deleteRule_returns204AndRuleRemovedFromList() throws Exception {
        CategoryResponse category = categoryService.createCategory(
                new CreateCategoryRequest("Transporte", null, null, null, 0), userId);

        String ruleBody = """
                {
                    "pattern": "uber",
                    "categoryId": "%s",
                    "priority": 1
                }
                """.formatted(category.id());

        String ruleJson = mockMvc.perform(post("/api/v1/categories/rules")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ruleBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String ruleId = JsonPath.read(ruleJson, "$.id");

        mockMvc.perform(delete("/api/v1/categories/rules/{id}", ruleId)
                        .header("Authorization", bearer()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/categories/rules")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String bearer() {
        return "Bearer " + token;
    }
}
