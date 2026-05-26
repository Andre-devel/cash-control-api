package com.cashcontrol.api.controller;

import com.cashcontrol.api.domain.entity.DeleteRecurrenceStrategy;
import com.cashcontrol.api.dto.request.CreateRecurrenceRequest;
import com.cashcontrol.api.dto.request.EditRecurrenceRequest;
import com.cashcontrol.api.dto.request.PauseRecurrenceRequest;
import com.cashcontrol.api.dto.response.DeleteRecurrenceResult;
import com.cashcontrol.api.dto.response.EditRecurrenceResult;
import com.cashcontrol.api.dto.response.ErrorResponse;
import com.cashcontrol.api.dto.response.RecurrenceCreationResponse;
import com.cashcontrol.api.dto.response.RecurrenceRuleResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.RecurrenceService;
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

@Tag(name = "Recurring Transactions", description = "Recurrence rules creation, editing, pausing, resuming, and deletion")
@RestController
@RequestMapping("/api/v1/recurrences")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class RecurrenceController {

    private final RecurrenceService recurrenceService;

    @Operation(summary = "Create recurrence rule",
            description = "Creates a recurrence rule and generates the first transaction instance plus up to 12 future instances.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Recurrence rule created"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Business rule violation (archived account)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public RecurrenceCreationResponse createRecurrence(
            @Valid @RequestBody CreateRecurrenceRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return recurrenceService.createRecurrence(request, principal.getUser().getId());
    }

    @Operation(summary = "List recurrence rules",
            description = "Returns all active (non-deleted) recurrence rules for the authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of recurrence rules"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<RecurrenceRuleResponse> listRecurrences(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return recurrenceService.listRecurrences(principal.getUser().getId());
    }

    @Operation(summary = "Get recurrence rule",
            description = "Returns a single recurrence rule by ID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recurrence rule"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Recurrence rule not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public RecurrenceRuleResponse getRecurrence(
            @Parameter(description = "Recurrence rule UUID", required = true) @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return recurrenceService.getRecurrence(id, principal.getUser().getId());
    }

    @Operation(summary = "Edit recurrence series",
            description = "Updates amount, description, category, or account on the rule and all future PENDING instances.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rule updated; affected instance count returned"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Recurrence rule not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Rule is deleted",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public EditRecurrenceResult editSeries(
            @Parameter(description = "Recurrence rule UUID", required = true) @PathVariable UUID id,
            @Valid @RequestBody EditRecurrenceRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return recurrenceService.editSeries(id, request, principal.getUser().getId());
    }

    @Operation(summary = "Pause recurrence",
            description = "Pauses the recurrence rule and cancels all future PENDING instances.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recurrence paused"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Recurrence rule not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Rule is not ACTIVE",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/pause")
    @PreAuthorize("isAuthenticated()")
    public RecurrenceRuleResponse pauseRecurrence(
            @Parameter(description = "Recurrence rule UUID", required = true) @PathVariable UUID id,
            @RequestBody(required = false) PauseRecurrenceRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        PauseRecurrenceRequest req = request != null ? request : new PauseRecurrenceRequest(null);
        return recurrenceService.pauseRecurrence(id, req, principal.getUser().getId());
    }

    @Operation(summary = "Resume recurrence",
            description = "Resumes a paused recurrence rule and regenerates instances from today.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recurrence resumed"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Recurrence rule not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Rule is not PAUSED",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/resume")
    @PreAuthorize("isAuthenticated()")
    public RecurrenceRuleResponse resumeRecurrence(
            @Parameter(description = "Recurrence rule UUID", required = true) @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return recurrenceService.resumeRecurrence(id, principal.getUser().getId());
    }

    @Operation(summary = "Delete recurrence rule",
            description = "Soft-deletes the recurrence rule and cancels instances according to the specified strategy.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recurrence deleted; cancelled instance count returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Recurrence rule not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public DeleteRecurrenceResult deleteRecurrence(
            @Parameter(description = "Recurrence rule UUID", required = true) @PathVariable UUID id,
            @Parameter(description = "Deletion strategy: FUTURE_ONLY or ALL", required = true)
            @RequestParam(defaultValue = "FUTURE_ONLY") DeleteRecurrenceStrategy strategy,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return recurrenceService.deleteRecurrence(id, strategy, principal.getUser().getId());
    }
}
