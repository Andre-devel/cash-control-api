package com.cashcontrol.api.controller;

/**
 * @author Andre
 * 04 de Novembro. de 2026
 */

import com.cashcontrol.api.dto.request.SavingsGoalRequest;
import com.cashcontrol.api.dto.response.ErrorResponse;
import com.cashcontrol.api.dto.response.SavingsGoalResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.SavingsGoalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "Savings goals", description = "Savings targets tracked against an account balance or a holding's net value")
@RestController
@RequestMapping("/api/v1/goals")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class SavingsGoalController {

    private final SavingsGoalService goalService;

    @Operation(summary = "List goals", description = "Goals with their current progress, oldest first.")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<SavingsGoalResponse> list(@AuthenticationPrincipal AuthenticatedUser principal,
                                          @Parameter(description = "Include archived goals (default false)")
                                          @RequestParam(defaultValue = "false") boolean includeArchived) {
        return goalService.list(principal.getUser().getId(), includeArchived, LocalDate.now());
    }

    @Operation(summary = "Get a goal")
    @ApiResponse(responseCode = "404", description = "Goal not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public SavingsGoalResponse get(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID id) {
        return goalService.get(id, principal.getUser().getId(), LocalDate.now());
    }

    @Operation(summary = "Create a goal")
    @ApiResponse(responseCode = "404", description = "Linked account or holding not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "Both an account and a holding were linked",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public SavingsGoalResponse create(@AuthenticationPrincipal AuthenticatedUser principal,
                                      @RequestBody @Valid SavingsGoalRequest request) {
        return goalService.create(request, principal.getUser().getId(), LocalDate.now());
    }

    @Operation(summary = "Update a goal", description = "Replaces every field, including the link.")
    @ApiResponse(responseCode = "404", description = "Goal, account or holding not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "Both an account and a holding were linked",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PutMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public SavingsGoalResponse update(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID id,
                                      @RequestBody @Valid SavingsGoalRequest request) {
        return goalService.update(id, request, principal.getUser().getId(), LocalDate.now());
    }

    @Operation(summary = "Delete a goal",
            description = "Deletes a goal without progress; a goal with progress is archived instead. Deleting an archived goal removes it.")
    @ApiResponse(responseCode = "404", description = "Goal not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void delete(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID id) {
        goalService.delete(id, principal.getUser().getId(), LocalDate.now());
    }

    @Operation(summary = "Unarchive a goal")
    @ApiResponse(responseCode = "404", description = "Goal not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/{id}/unarchive")
    @PreAuthorize("isAuthenticated()")
    public SavingsGoalResponse unarchive(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID id) {
        return goalService.unarchive(id, principal.getUser().getId(), LocalDate.now());
    }
}
