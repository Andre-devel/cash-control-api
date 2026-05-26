package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ConflictException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.response.CategoryResponse;
import com.cashcontrol.api.dto.response.CategoryRuleResponse;
import com.cashcontrol.api.dto.response.CategorySuggestionResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.CategoryService;
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

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
class CategoryControllerTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @MockitoBean private CategoryService categoryService;

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

    // ── GET /api/v1/categories ────────────────────────────────────────────────

    @Test
    void listCategories_returns200() throws Exception {
        UUID catId = UUID.randomUUID();
        when(categoryService.listCategories(eq(userId), eq(false), eq(false)))
                .thenReturn(List.of(buildCategoryResponse(catId, "Food", null)));

        mockMvc.perform(get("/api/v1/categories")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Food"))
                .andExpect(jsonPath("$[0].id").value(catId.toString()));
    }

    @Test
    void listCategories_withIncludeHidden_passesFlag() throws Exception {
        when(categoryService.listCategories(eq(userId), eq(true), eq(false)))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/v1/categories")
                        .with(user(principal))
                        .param("includeHidden", "true"))
                .andExpect(status().isOk());
    }

    @Test
    void listCategories_withIncludeArchived_passesFlag() throws Exception {
        when(categoryService.listCategories(eq(userId), eq(false), eq(true)))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/v1/categories")
                        .with(user(principal))
                        .param("includeArchived", "true"))
                .andExpect(status().isOk());
    }

    @Test
    void listCategories_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /api/v1/categories ───────────────────────────────────────────────

    @Test
    void createCategory_returns201() throws Exception {
        UUID catId = UUID.randomUUID();
        when(categoryService.createCategory(any(), eq(userId)))
                .thenReturn(buildCategoryResponse(catId, "Groceries", null));

        mockMvc.perform(post("/api/v1/categories")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Groceries\",\"color\":\"#FF5733\",\"icon\":\"shopping\",\"sortOrder\":0}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Groceries"))
                .andExpect(jsonPath("$.id").value(catId.toString()));
    }

    @Test
    void createCategory_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/categories")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"sortOrder\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createCategory_nameTooLong_returns400() throws Exception {
        String longName = "A".repeat(101);
        mockMvc.perform(post("/api/v1/categories")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + longName + "\",\"sortOrder\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createCategory_invalidColorFormat_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/categories")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test\",\"color\":\"not-a-color\",\"sortOrder\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createCategory_duplicateName_returns409() throws Exception {
        when(categoryService.createCategory(any(), eq(userId)))
                .thenThrow(new ConflictException("Category already exists"));

        mockMvc.perform(post("/api/v1/categories")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Food\",\"sortOrder\":0}"))
                .andExpect(status().isConflict());
    }

    @Test
    void createCategory_parentNotFound_returns404() throws Exception {
        when(categoryService.createCategory(any(), eq(userId)))
                .thenThrow(new ResourceNotFoundException("Parent category not found"));

        UUID parentId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/categories")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sub\",\"parentId\":\"" + parentId + "\",\"sortOrder\":0}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createCategory_thirdLevelNesting_returns422() throws Exception {
        when(categoryService.createCategory(any(), eq(userId)))
                .thenThrow(new BusinessRuleException("Maximum depth is 2"));

        UUID parentId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/categories")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"DeepSub\",\"parentId\":\"" + parentId + "\",\"sortOrder\":0}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createCategory_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Food\",\"sortOrder\":0}"))
                .andExpect(status().isUnauthorized());
    }

    // ── PUT /api/v1/categories/{id} ───────────────────────────────────────────

    @Test
    void editCategory_returns200() throws Exception {
        UUID catId = UUID.randomUUID();
        when(categoryService.editCategory(eq(catId), any(), eq(userId)))
                .thenReturn(buildCategoryResponse(catId, "Updated Name", null));

        mockMvc.perform(put("/api/v1/categories/" + catId)
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated Name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Name"));
    }

    @Test
    void editCategory_systemCategory_returns422() throws Exception {
        UUID catId = UUID.randomUUID();
        when(categoryService.editCategory(eq(catId), any(), eq(userId)))
                .thenThrow(new BusinessRuleException("System default categories cannot be edited"));

        mockMvc.perform(put("/api/v1/categories/" + catId)
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Name\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void editCategory_nameConflict_returns409() throws Exception {
        UUID catId = UUID.randomUUID();
        when(categoryService.editCategory(eq(catId), any(), eq(userId)))
                .thenThrow(new ConflictException("Name already exists"));

        mockMvc.perform(put("/api/v1/categories/" + catId)
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Conflict\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void editCategory_notFound_returns404() throws Exception {
        UUID catId = UUID.randomUUID();
        when(categoryService.editCategory(eq(catId), any(), eq(userId)))
                .thenThrow(new ResourceNotFoundException("Category not found"));

        mockMvc.perform(put("/api/v1/categories/" + catId)
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Name\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void editCategory_unauthenticated_returns401() throws Exception {
        UUID catId = UUID.randomUUID();
        mockMvc.perform(put("/api/v1/categories/" + catId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Name\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /api/v1/categories/{id}/hide ─────────────────────────────────────

    @Test
    void hideCategory_returns200() throws Exception {
        UUID catId = UUID.randomUUID();
        CategoryResponse hidden = buildCategoryResponse(catId, "Food", null, true, false);
        when(categoryService.setHidden(eq(catId), eq(true), eq(userId))).thenReturn(hidden);

        mockMvc.perform(post("/api/v1/categories/" + catId + "/hide")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isHidden").value(true));
    }

    @Test
    void hideCategory_notFound_returns404() throws Exception {
        UUID catId = UUID.randomUUID();
        when(categoryService.setHidden(eq(catId), eq(true), eq(userId)))
                .thenThrow(new ResourceNotFoundException("Category not found"));

        mockMvc.perform(post("/api/v1/categories/" + catId + "/hide")
                        .with(user(principal)))
                .andExpect(status().isNotFound());
    }

    @Test
    void hideCategory_unauthenticated_returns401() throws Exception {
        UUID catId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/categories/" + catId + "/hide"))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /api/v1/categories/{id}/show ─────────────────────────────────────

    @Test
    void showCategory_returns200() throws Exception {
        UUID catId = UUID.randomUUID();
        CategoryResponse visible = buildCategoryResponse(catId, "Food", null, false, false);
        when(categoryService.setHidden(eq(catId), eq(false), eq(userId))).thenReturn(visible);

        mockMvc.perform(post("/api/v1/categories/" + catId + "/show")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isHidden").value(false));
    }

    @Test
    void showCategory_notFound_returns404() throws Exception {
        UUID catId = UUID.randomUUID();
        when(categoryService.setHidden(eq(catId), eq(false), eq(userId)))
                .thenThrow(new ResourceNotFoundException("Category not found"));

        mockMvc.perform(post("/api/v1/categories/" + catId + "/show")
                        .with(user(principal)))
                .andExpect(status().isNotFound());
    }

    // ── POST /api/v1/categories/{id}/archive ──────────────────────────────────

    @Test
    void archiveCategory_returns200() throws Exception {
        UUID catId = UUID.randomUUID();
        CategoryResponse archived = buildCategoryResponse(catId, "Old Cat", null, false, true);
        when(categoryService.archiveCategory(eq(catId), eq(userId))).thenReturn(archived);

        mockMvc.perform(post("/api/v1/categories/" + catId + "/archive")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isArchived").value(true));
    }

    @Test
    void archiveCategory_systemCategory_returns422() throws Exception {
        UUID catId = UUID.randomUUID();
        when(categoryService.archiveCategory(eq(catId), eq(userId)))
                .thenThrow(new BusinessRuleException("System default categories cannot be archived"));

        mockMvc.perform(post("/api/v1/categories/" + catId + "/archive")
                        .with(user(principal)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void archiveCategory_notFound_returns404() throws Exception {
        UUID catId = UUID.randomUUID();
        when(categoryService.archiveCategory(eq(catId), eq(userId)))
                .thenThrow(new ResourceNotFoundException("Category not found"));

        mockMvc.perform(post("/api/v1/categories/" + catId + "/archive")
                        .with(user(principal)))
                .andExpect(status().isNotFound());
    }

    @Test
    void archiveCategory_unauthenticated_returns401() throws Exception {
        UUID catId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/categories/" + catId + "/archive"))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /api/v1/categories/{id}/unarchive ────────────────────────────────

    @Test
    void unarchiveCategory_returns200() throws Exception {
        UUID catId = UUID.randomUUID();
        CategoryResponse active = buildCategoryResponse(catId, "My Cat", null, false, false);
        when(categoryService.unarchiveCategory(eq(catId), eq(userId))).thenReturn(active);

        mockMvc.perform(post("/api/v1/categories/" + catId + "/unarchive")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isArchived").value(false));
    }

    @Test
    void unarchiveCategory_notFound_returns404() throws Exception {
        UUID catId = UUID.randomUUID();
        when(categoryService.unarchiveCategory(eq(catId), eq(userId)))
                .thenThrow(new ResourceNotFoundException("Category not found"));

        mockMvc.perform(post("/api/v1/categories/" + catId + "/unarchive")
                        .with(user(principal)))
                .andExpect(status().isNotFound());
    }

    // ── GET /api/v1/categories/suggest ───────────────────────────────────────

    @Test
    void suggestCategories_withDescription_returns200() throws Exception {
        UUID catId = UUID.randomUUID();
        when(categoryService.suggestCategory(eq("Netflix"), eq(userId)))
                .thenReturn(List.of(new CategorySuggestionResponse(catId, "Subscriptions", null, null, 5L)));

        mockMvc.perform(get("/api/v1/categories/suggest")
                        .with(user(principal))
                        .param("description", "Netflix"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].categoryName").value("Subscriptions"))
                .andExpect(jsonPath("$[0].matchCount").value(5));
    }

    @Test
    void suggestCategories_noDescription_returns200() throws Exception {
        UUID catId = UUID.randomUUID();
        when(categoryService.suggestCategory(eq(null), eq(userId)))
                .thenReturn(List.of(new CategorySuggestionResponse(catId, "Food", null, null, 10L)));

        mockMvc.perform(get("/api/v1/categories/suggest")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].categoryName").value("Food"));
    }

    @Test
    void suggestCategories_noHistory_returnsEmptyList() throws Exception {
        when(categoryService.suggestCategory(any(), eq(userId)))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/v1/categories/suggest")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void suggestCategories_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/categories/suggest"))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /api/v1/categories/rules ─────────────────────────────────────────

    @Test
    void createRule_returns201() throws Exception {
        UUID catId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        when(categoryService.createRule(any(), eq(userId)))
                .thenReturn(buildRuleResponse(ruleId, catId, "Netflix"));

        mockMvc.perform(post("/api/v1/categories/rules")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pattern\":\"Netflix\",\"categoryId\":\"" + catId + "\",\"priority\":0}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pattern").value("Netflix"))
                .andExpect(jsonPath("$.id").value(ruleId.toString()));
    }

    @Test
    void createRule_blankPattern_returns400() throws Exception {
        UUID catId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/categories/rules")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pattern\":\"\",\"categoryId\":\"" + catId + "\",\"priority\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRule_missingCategoryId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/categories/rules")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pattern\":\"Netflix\",\"priority\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRule_categoryNotFound_returns404() throws Exception {
        when(categoryService.createRule(any(), eq(userId)))
                .thenThrow(new ResourceNotFoundException("Category not found"));

        UUID catId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/categories/rules")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pattern\":\"keyword\",\"categoryId\":\"" + catId + "\",\"priority\":0}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createRule_subcategoryMismatch_returns422() throws Exception {
        when(categoryService.createRule(any(), eq(userId)))
                .thenThrow(new BusinessRuleException("Subcategory does not belong to the category"));

        UUID catId = UUID.randomUUID();
        UUID subId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/categories/rules")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pattern\":\"keyword\",\"categoryId\":\"" + catId + "\"," +
                                 "\"subcategoryId\":\"" + subId + "\",\"priority\":0}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createRule_unauthenticated_returns401() throws Exception {
        UUID catId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/categories/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pattern\":\"Netflix\",\"categoryId\":\"" + catId + "\",\"priority\":0}"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/v1/categories/rules ──────────────────────────────────────────

    @Test
    void listRules_returns200() throws Exception {
        UUID catId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        when(categoryService.listRules(eq(userId)))
                .thenReturn(List.of(buildRuleResponse(ruleId, catId, "McDonald's")));

        mockMvc.perform(get("/api/v1/categories/rules")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pattern").value("McDonald's"))
                .andExpect(jsonPath("$[0].id").value(ruleId.toString()));
    }

    @Test
    void listRules_empty_returns200() throws Exception {
        when(categoryService.listRules(eq(userId))).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/v1/categories/rules")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void listRules_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/categories/rules"))
                .andExpect(status().isUnauthorized());
    }

    // ── DELETE /api/v1/categories/rules/{id} ──────────────────────────────────

    @Test
    void deleteRule_returns204() throws Exception {
        UUID ruleId = UUID.randomUUID();
        doNothing().when(categoryService).deleteRule(eq(ruleId), eq(userId));

        mockMvc.perform(delete("/api/v1/categories/rules/" + ruleId)
                        .with(user(principal)))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteRule_notFound_returns404() throws Exception {
        UUID ruleId = UUID.randomUUID();
        doThrow(new ResourceNotFoundException("Rule not found"))
                .when(categoryService).deleteRule(eq(ruleId), eq(userId));

        mockMvc.perform(delete("/api/v1/categories/rules/" + ruleId)
                        .with(user(principal)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteRule_unauthenticated_returns401() throws Exception {
        UUID ruleId = UUID.randomUUID();
        mockMvc.perform(delete("/api/v1/categories/rules/" + ruleId))
                .andExpect(status().isUnauthorized());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private AuthenticatedUser buildAuthenticatedUser(UUID id) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setEmail("category-ctrl-" + id + "@example.com");
        user.setAccountStatus(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_ACTIVE));
        user.setAuthOrigin(TestEntityFactory.authOrigin(UserSlugConstants.ORIGIN_LOCAL));
        user.setCredentialsUpdatedAt(Instant.now());
        return new AuthenticatedUser(user, createAuthorityList());
    }

    private CategoryResponse buildCategoryResponse(UUID id, String name, UUID parentId) {
        return buildCategoryResponse(id, name, parentId, false, false);
    }

    private CategoryResponse buildCategoryResponse(UUID id, String name, UUID parentId,
                                                    boolean isHidden, boolean isArchived) {
        return new CategoryResponse(
                id, userId, parentId, null,
                name, "#FF5733", "tag",
                0, false, isHidden, isArchived,
                isArchived ? Instant.now() : null,
                Collections.emptyList(),
                Instant.now(), Instant.now()
        );
    }

    private CategoryRuleResponse buildRuleResponse(UUID ruleId, UUID catId, String pattern) {
        return new CategoryRuleResponse(
                ruleId, userId,
                pattern, catId, "Food",
                null, null,
                null, null,
                0, true,
                Instant.now(), Instant.now()
        );
    }
}
