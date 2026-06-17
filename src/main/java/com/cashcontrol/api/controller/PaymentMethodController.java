package com.cashcontrol.api.controller;

import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
import com.cashcontrol.api.dto.response.ErrorResponse;
import com.cashcontrol.api.dto.response.PaymentMethodResponse;
import com.cashcontrol.api.repository.PaymentMethodRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

@Tag(name = "Payment Methods", description = "Lookup list of supported payment method types")
@RestController
@RequestMapping("/api/v1/payment-methods")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class PaymentMethodController {

    private static final List<PaymentMethodSlug> DISPLAY_ORDER = List.of(
            PaymentMethodSlug.CASH,
            PaymentMethodSlug.PIX,
            PaymentMethodSlug.DEBIT_CARD,
            PaymentMethodSlug.CREDIT_CARD,
            PaymentMethodSlug.BANK_TRANSFER,
            PaymentMethodSlug.BOLETO,
            PaymentMethodSlug.OTHER
    );

    private final PaymentMethodRepository paymentMethodRepository;

    @Operation(summary = "List payment methods", description = "Returns all active payment methods in fixed display order.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Payment methods returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<PaymentMethodResponse> listPaymentMethods() {
        return paymentMethodRepository.findAllByIsActiveTrue()
                .stream()
                .sorted(Comparator.comparingInt(pm -> {
                    int idx = DISPLAY_ORDER.indexOf(pm.getSlug());
                    return idx < 0 ? Integer.MAX_VALUE : idx;
                }))
                .map(pm -> new PaymentMethodResponse(pm.getId(), pm.getSlug(), pm.getName()))
                .toList();
    }
}
