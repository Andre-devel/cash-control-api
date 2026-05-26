package com.cashcontrol.api.dto.response;

public record EditRecurrenceResult(
        RecurrenceRuleResponse rule,
        int updatedInstances
) {}
