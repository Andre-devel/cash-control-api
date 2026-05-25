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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;



@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class OpenApiTest {

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
    void apiDocsEndpointReturns200() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }

    @Test
    void apiDocsEndpointIsAccessibleWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }

    @Test
    void apiDocsResponseIsValidOpenApiJson() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andReturn();

        String json = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(json);

        assertThat(root.has("openapi")).isTrue();
        assertThat(root.get("openapi").asText()).startsWith("3.");
        assertThat(root.has("info")).isTrue();
        assertThat(root.has("paths")).isTrue();
        assertThat(root.has("components")).isTrue();
    }

    @Test
    void apiDocsTitleMatchesExpectedValue() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Java Auth Template API"));
    }

    @Test
    void apiDocsContainsBearerAuthSecurityScheme() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(json);

        JsonNode securitySchemes = root.path("components").path("securitySchemes");
        assertThat(securitySchemes.has("bearerAuth")).isTrue();
        assertThat(securitySchemes.path("bearerAuth").path("type").asText()).isEqualTo("http");
        assertThat(securitySchemes.path("bearerAuth").path("scheme").asText()).isEqualTo("bearer");
        assertThat(securitySchemes.path("bearerAuth").path("bearerFormat").asText()).isEqualTo("JWT");
    }

    @Test
    void apiDocsContainsAuthPaths() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        JsonNode paths = objectMapper.readTree(json).path("paths");

        assertThat(paths.has("/api/v1/auth/register")).isTrue();
        assertThat(paths.has("/api/v1/auth/login")).isTrue();
        assertThat(paths.has("/api/v1/auth/logout")).isTrue();
        assertThat(paths.has("/api/v1/auth/password-reset/request")).isTrue();
        assertThat(paths.has("/api/v1/auth/password-reset/confirm")).isTrue();
        assertThat(paths.has("/api/v1/auth/email/verify")).isTrue();
    }

    @Test
    void apiDocsContainsUserPaths() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        JsonNode paths = objectMapper.readTree(json).path("paths");

        assertThat(paths.has("/api/v1/users")).isTrue();
        assertThat(paths.has("/api/v1/users/me")).isTrue();
    }

    @Test
    void apiDocsContainsRolePaths() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        JsonNode paths = objectMapper.readTree(json).path("paths");

        assertThat(paths.has("/api/v1/roles")).isTrue();
    }

    @Test
    void apiDocsContainsPermissionPaths() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        JsonNode paths = objectMapper.readTree(json).path("paths");

        assertThat(paths.has("/api/v1/permissions")).isTrue();
    }

    @Test
    void apiDocsContainsAuditPaths() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        JsonNode paths = objectMapper.readTree(json).path("paths");

        assertThat(paths.has("/api/v1/audit")).isTrue();
        assertThat(paths.has("/api/v1/audit/summary")).isTrue();
    }

    @Test
    void apiDocsContainsAdminSecurityPaths() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        JsonNode paths = objectMapper.readTree(json).path("paths");

        assertThat(paths.has("/api/v1/admin/security/force-reauth")).isTrue();
        assertThat(paths.has("/api/v1/admin/security/lock")).isTrue();
        assertThat(paths.has("/api/v1/admin/security/unlock")).isTrue();
    }

    @Test
    void apiDocsResponseContainsNoInternalPackagePaths() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        assertThat(json).doesNotContain("com.cashcontrol.api");
    }

    @Test
    void swaggerUiIsAccessible() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
