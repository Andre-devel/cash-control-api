package com.cashcontrol.api.controller;

/**
 * @author Andre
 * 30 de Outubro. de 2026
 */

import com.cashcontrol.api.dto.request.PushSubscriptionRequest;
import com.cashcontrol.api.dto.request.PushUnsubscribeRequest;
import com.cashcontrol.api.dto.response.ErrorResponse;
import com.cashcontrol.api.dto.response.PushConfigResponse;
import com.cashcontrol.api.dto.response.PushSubscriptionResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.push.PushService;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Push", description = "Web Push notifications, turned on per device")
@RestController
@RequestMapping("/api/v1/push")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class PushController {

    private final PushService pushService;

    @Operation(summary = "Push config", description = "Whether the server sends notifications, and the VAPID public key to subscribe with.")
    @GetMapping("/vapid-key")
    @PreAuthorize("isAuthenticated()")
    public PushConfigResponse config() {
        return pushService.config();
    }

    @Operation(summary = "Devices", description = "The user's devices that receive notifications.")
    @GetMapping("/subscriptions")
    @PreAuthorize("isAuthenticated()")
    public List<PushSubscriptionResponse> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return pushService.list(principal.getUser().getId());
    }

    @Operation(summary = "Turn on this device", description = "Saves (or updates) the browser subscription of this device.")
    @ApiResponse(responseCode = "422", description = "Push not configured, or not a known push service",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/subscriptions")
    @PreAuthorize("isAuthenticated()")
    public PushSubscriptionResponse subscribe(@AuthenticationPrincipal AuthenticatedUser principal,
                                              @RequestBody @Valid PushSubscriptionRequest request) {
        return pushService.subscribe(principal.getUser().getId(), request);
    }

    @Operation(summary = "Turn off this device")
    @DeleteMapping("/subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void unsubscribe(@AuthenticationPrincipal AuthenticatedUser principal,
                            @RequestBody @Valid PushUnsubscribeRequest request) {
        pushService.unsubscribe(principal.getUser().getId(), request.endpoint());
    }
}
