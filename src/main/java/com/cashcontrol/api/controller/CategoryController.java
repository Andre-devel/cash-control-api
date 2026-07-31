package com.cashcontrol.api.controller;

import com.cashcontrol.api.dto.request.CreateCategoryRequest;
import com.cashcontrol.api.dto.request.CreateCategoryRuleRequest;
import com.cashcontrol.api.dto.request.EditCategoryRequest;
import com.cashcontrol.api.dto.response.CategoryResponse;
import com.cashcontrol.api.dto.response.CategoryRuleResponse;
import com.cashcontrol.api.dto.response.CategorySuggestionResponse;
import com.cashcontrol.api.dto.response.ErrorResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Categories", description = "Category and subcategory management, auto-suggestion, and category rules")
@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class CategoryController {

    private final CategoryService categoryService;

    @Operation(summary = "List categories",
            description = "Returns all system and user-defined categories as a nested tree. " +
                          "Subcategories are nested under their parent. Hidden and archived categories " +
                          "are excluded by default.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Category tree"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<CategoryResponse> listCategories(
            @Parameter(description = "Include hidden categories (default false)")
            @RequestParam(defaultValue = "false") boolean includeHidden,
            @Parameter(description = "Include archived categories (default false)")
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return categoryService.listCategories(principal.getUser().getId(), includeHidden, includeArchived);
    }

    @Operation(summary = "Create category",
            description = "Creates a new user-defined root category or subcategory. " +
                          "To create a subcategory, provide a parentId pointing to a root-level category. " +
                          "Subcategories cannot be nested more than two levels deep.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Category created"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Parent category not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Category name already exists in this scope",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Maximum nesting depth exceeded",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public CategoryResponse createCategory(
            @Valid @RequestBody CreateCategoryRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return categoryService.createCategory(request, principal.getUser().getId());
    }

    @Operation(summary = "Edit category",
            description = "Updates name, color, icon, or sort order of a user-defined category. " +
                          "Categorias padrão do sistema não podem ser editadas.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Category updated"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Category not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Category name conflict",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "System categories cannot be edited",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public CategoryResponse editCategory(
            @Parameter(description = "Category UUID", required = true) @PathVariable UUID id,
            @Valid @RequestBody EditCategoryRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return categoryService.editCategory(id, request, principal.getUser().getId());
    }

    @Operation(summary = "Hide category",
            description = "Hides the category from transaction pickers. Works on both system and user-defined categories.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Category hidden"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Category not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/hide")
    @PreAuthorize("isAuthenticated()")
    public CategoryResponse hideCategory(
            @Parameter(description = "Category UUID", required = true) @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return categoryService.setHidden(id, true, principal.getUser().getId());
    }

    @Operation(summary = "Show category",
            description = "Unhides the category, making it available in transaction pickers again.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Category visible"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Category not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/show")
    @PreAuthorize("isAuthenticated()")
    public CategoryResponse showCategory(
            @Parameter(description = "Category UUID", required = true) @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return categoryService.setHidden(id, false, principal.getUser().getId());
    }

    @Operation(summary = "Archive category",
            description = "Archives a user-defined category and all its subcategories. " +
                          "Archived categories cannot receive new transactions. System default categories cannot be archived.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Category archived"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Category not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "System categories cannot be archived",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/archive")
    @PreAuthorize("isAuthenticated()")
    public CategoryResponse archiveCategory(
            @Parameter(description = "Category UUID", required = true) @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return categoryService.archiveCategory(id, principal.getUser().getId());
    }

    @Operation(summary = "Unarchive category",
            description = "Restores an archived user-defined category to active status.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Category unarchived"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Category not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/unarchive")
    @PreAuthorize("isAuthenticated()")
    public CategoryResponse unarchiveCategory(
            @Parameter(description = "Category UUID", required = true) @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return categoryService.unarchiveCategory(id, principal.getUser().getId());
    }

    @Operation(summary = "Suggest categories",
            description = "Returns up to 5 category suggestions ranked by frequency of use in transactions " +
                          "with similar descriptions. Falls back to most-used categories when no history matches.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Category suggestions"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/suggest")
    @PreAuthorize("isAuthenticated()")
    public List<CategorySuggestionResponse> suggestCategories(
            @Parameter(description = "Transaction description to match against") @RequestParam(required = false) String description,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return categoryService.suggestCategory(description, principal.getUser().getId());
    }

    @Operation(summary = "Create category rule",
            description = "Creates an auto-categorization rule that assigns a category to transactions " +
                          "whose description matches the specified pattern.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Rule created"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Category or account not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Subcategory does not belong to the category",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public CategoryRuleResponse createRule(
            @Valid @RequestBody CreateCategoryRuleRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return categoryService.createRule(request, principal.getUser().getId());
    }

    @Operation(summary = "List category rules",
            description = "Returns all active category rules for the authenticated user, ordered by priority.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of category rules"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/rules")
    @PreAuthorize("isAuthenticated()")
    public List<CategoryRuleResponse> listRules(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return categoryService.listRules(principal.getUser().getId());
    }

    @Operation(summary = "Delete category rule",
            description = "Permanently deletes a category rule. Existing transactions are not recategorized.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Rule deleted"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Rule not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void deleteRule(
            @Parameter(description = "Rule UUID", required = true) @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        categoryService.deleteRule(id, principal.getUser().getId());
    }
}
