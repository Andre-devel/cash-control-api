package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.BankAccountKind;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Account the bank shares through the connection and the app account or card it is linked to")
public record BankConnectionAccountResponse(
        UUID id,
        BankAccountKind kind,
        String name,
        @Schema(description = "Account number or card's last digits, as the bank sends it") String number,
        UUID accountId,
        UUID creditCardId
) {}
