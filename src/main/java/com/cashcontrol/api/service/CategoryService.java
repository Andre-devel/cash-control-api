package com.cashcontrol.api.service;

import com.cashcontrol.api.dto.request.CreateCategoryRequest;
import com.cashcontrol.api.dto.request.CreateCategoryRuleRequest;
import com.cashcontrol.api.dto.request.EditCategoryRequest;
import com.cashcontrol.api.dto.response.CategoryResponse;
import com.cashcontrol.api.dto.response.CategoryRuleResponse;
import com.cashcontrol.api.dto.response.CategorySuggestionResponse;

import java.util.List;
import java.util.UUID;

public interface CategoryService {

    List<CategoryResponse> listCategories(UUID userId, boolean includeHidden, boolean includeArchived);

    CategoryResponse createCategory(CreateCategoryRequest request, UUID userId);

    CategoryResponse editCategory(UUID id, EditCategoryRequest request, UUID userId);

    CategoryResponse setHidden(UUID id, boolean hidden, UUID userId);

    CategoryResponse archiveCategory(UUID id, UUID userId);

    CategoryResponse unarchiveCategory(UUID id, UUID userId);

    List<CategorySuggestionResponse> suggestCategory(String description, UUID userId);

    CategoryRuleResponse createRule(CreateCategoryRuleRequest request, UUID userId);

    List<CategoryRuleResponse> listRules(UUID userId);

    void deleteRule(UUID id, UUID userId);
}
