package com.cashcontrol.api.controller;

/**
 * @author Andre
 * 04 de Novembro. de 2026
 */

import com.cashcontrol.api.dto.response.ErrorResponse;
import com.cashcontrol.api.dto.response.SearchResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Search", description = "Global search across the user's financial data")
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class SearchController {

    private final SearchService searchService;

    @Operation(summary = "Global search",
            description = "Up to 10 matches per group: transactions (description or counterparty, or exact amount "
                    + "when q is a pt-BR number such as 615,23), accounts, credit cards, categories, recurrences "
                    + "and installment series. Only the caller's data; deleted records are left out.")
    @ApiResponse(responseCode = "400", description = "q has fewer than 2 characters",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public SearchResponse search(@AuthenticationPrincipal AuthenticatedUser principal,
                                 @Parameter(description = "Search text, at least 2 characters", example = "mercado")
                                 @RequestParam
                                 @Pattern(regexp = "(?s)\\s*\\S.*\\S\\s*", message = "A busca precisa de ao menos 2 caracteres.")
                                 @Size(max = 100, message = "A busca aceita no máximo 100 caracteres.")
                                 String q) {
        return searchService.search(q, principal.getUser().getId());
    }
}
