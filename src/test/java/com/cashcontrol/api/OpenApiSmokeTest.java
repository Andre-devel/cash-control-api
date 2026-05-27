package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class OpenApiSmokeTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void apiDocsReturns200() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }

    @Test
    void apiDocsContainsExpectedMinimumPathCount() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        JsonNode paths = objectMapper.readTree(json).path("paths");

        int pathCount = paths.size();
        assertThat(pathCount)
                .as("Expected at least 50 API paths covering auth, accounts, transactions, " +
                    "installments, recurrences, categories, credit cards, and dashboard endpoints")
                .isGreaterThanOrEqualTo(50);
    }

    @Test
    void apiDocsContainsAllCashControlDomains() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        JsonNode paths = objectMapper.readTree(json).path("paths");

        assertThat(paths.has("/api/v1/accounts")).as("accounts domain missing").isTrue();
        assertThat(paths.has("/api/v1/transactions")).as("transactions domain missing").isTrue();
        assertThat(paths.has("/api/v1/installments")).as("installments domain missing").isTrue();
        assertThat(paths.has("/api/v1/recurrences")).as("recurrences domain missing").isTrue();
        assertThat(paths.has("/api/v1/categories")).as("categories domain missing").isTrue();
        assertThat(paths.has("/api/v1/cards")).as("credit cards domain missing").isTrue();
        assertThat(paths.has("/api/v1/dashboard/overview")).as("dashboard domain missing").isTrue();
    }

    @Test
    void apiDocsHasBearerAuthSecuritySchemeAndGlobalRequirement() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(json);

        JsonNode bearerAuth = root.path("components").path("securitySchemes").path("bearerAuth");
        assertThat(bearerAuth.isMissingNode()).isFalse();
        assertThat(bearerAuth.path("type").asText()).isEqualTo("http");
        assertThat(bearerAuth.path("scheme").asText()).isEqualTo("bearer");
        assertThat(bearerAuth.path("bearerFormat").asText()).isEqualTo("JWT");

        JsonNode security = root.path("security");
        assertThat(security.isArray()).isTrue();
        assertThat(security.size()).isGreaterThan(0);
    }

    @Test
    void swaggerUiIsAccessibleWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
