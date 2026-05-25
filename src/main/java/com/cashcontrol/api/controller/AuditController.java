package com.cashcontrol.api.controller;

import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.dto.request.AuditLogFilterRequest;
import com.cashcontrol.api.dto.response.AuditLogResponse;
import com.cashcontrol.api.dto.response.ErrorResponse;
import com.cashcontrol.api.dto.response.PageResponse;
import com.cashcontrol.api.dto.response.SecuritySummaryResponse;
import com.cashcontrol.api.service.AdminSecurityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Audit", description = "Read-only access to the security audit event log and summary statistics. Requires `audit:view` authority.")
@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class AuditController {

    private final AuditService auditService;
    private final AdminSecurityService adminSecurityService;

    @Operation(
            summary = "Query audit log",
            description = "Returns a paginated, filtered list of audit events. "
                    + "Supports filtering by event type, actor, target user, outcome, and time range. "
                    + "Read-only. Requires `audit:view` authority."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paginated audit log returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing `audit:view` authority",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    @PreAuthorize("hasAuthority('audit:view')")
    public PageResponse<AuditLogResponse> queryAuditLogs(
            AuditLogFilterRequest filter,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(auditService.queryAuditLogs(filter, pageable));
    }

    @Operation(
            summary = "Get user audit timeline",
            description = "Returns a chronological audit timeline for a specific user. "
                    + "Includes all events where the user was the actor or target. "
                    + "Read-only. Requires `audit:view` authority."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User audit timeline returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing `audit:view` authority",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "User not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/users/{userId}")
    @PreAuthorize("hasAuthority('audit:view')")
    public PageResponse<AuditLogResponse> getUserAuditTimeline(
            @Parameter(description = "Target user UUID", required = true) @PathVariable UUID userId,
            @PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.from(auditService.getUserAuditTimeline(userId, pageable));
    }

    @Operation(
            summary = "Get security summary",
            description = "Returns aggregate security statistics: recent failure counts, locked accounts, and activity trends. "
                    + "Read-only. Requires `audit:view` authority."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Security summary returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing `audit:view` authority",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('audit:view')")
    public SecuritySummaryResponse getSecuritySummary() {
        return adminSecurityService.getSecuritySummary();
    }
}
