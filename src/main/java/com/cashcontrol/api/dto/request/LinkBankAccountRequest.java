package com.cashcontrol.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Where the bank account's entries go: accountId for a BANK account, creditCardId for a CREDIT one. Both null unlinks.")
public record LinkBankAccountRequest(
        UUID accountId,
        UUID creditCardId
) {}
