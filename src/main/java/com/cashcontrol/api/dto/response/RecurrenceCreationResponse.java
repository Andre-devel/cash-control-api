package com.cashcontrol.api.dto.response;

public record RecurrenceCreationResponse(
        RecurrenceRuleResponse rule,
        TransactionDetailResponse firstInstance
) {}
