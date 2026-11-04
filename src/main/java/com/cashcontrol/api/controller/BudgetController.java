package com.cashcontrol.api.controller;

/**
 * @author Andre
 * 31 de Outubro. de 2026
 */

import com.cashcontrol.api.dto.request.CategoryBudgetRequest;
import com.cashcontrol.api.dto.response.BudgetHistoryResponse;
import com.cashcontrol.api.dto.response.BudgetProgressResponse;
import com.cashcontrol.api.dto.response.CategoryBudgetResponse;
import com.cashcontrol.api.dto.response.ErrorResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.budget.BudgetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.UUID;

@Tag(name = "Budgets", description = "Monthly spending caps per category")
@RestController
@RequestMapping("/api/v1/budgets")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class BudgetController {

    private final BudgetService budgetService;

    @Operation(summary = "Budget progress", description = "Spending against each category cap in the month, by purchase date.")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public BudgetProgressResponse progress(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "Month in YYYY-MM format; current month when omitted")
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return budgetService.progress(principal.getUser().getId(), month != null ? month : YearMonth.now());
    }

    @Operation(summary = "Budget history", description = "Cap against spending per category for each of the last N months up to `until`, by purchase date.")
    @ApiResponse(responseCode = "400", description = "months outside 1..12 or malformed until",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/history")
    @PreAuthorize("isAuthenticated()")
    public BudgetHistoryResponse history(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "Number of months, 1 to 12 (default 6)")
            @RequestParam(defaultValue = "6") @Min(1) @Max(12) int months,
            @Parameter(description = "Last month in YYYY-MM format; current month when omitted")
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth until) {
        return budgetService.history(principal.getUser().getId(), until != null ? until : YearMonth.now(), months);
    }

    @Operation(summary = "Set a cap", description = "Creates or replaces the default cap of the category, or only the cap of one month.")
    @ApiResponse(responseCode = "404", description = "Category not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "Archived category",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PutMapping("/{categoryId}")
    @PreAuthorize("isAuthenticated()")
    public CategoryBudgetResponse save(@AuthenticationPrincipal AuthenticatedUser principal,
                                       @PathVariable UUID categoryId,
                                       @RequestBody @Valid CategoryBudgetRequest request) {
        return budgetService.save(principal.getUser().getId(), categoryId, request.amount(), request.month());
    }

    @Operation(summary = "Remove a cap", description = "Removes the default cap, or only the adjustment of the given month.")
    @ApiResponse(responseCode = "404", description = "No such cap",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @DeleteMapping("/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void delete(@AuthenticationPrincipal AuthenticatedUser principal,
                       @PathVariable UUID categoryId,
                       @Parameter(description = "Month in YYYY-MM format; the default cap when omitted")
                       @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        budgetService.delete(principal.getUser().getId(), categoryId, month);
    }
}
