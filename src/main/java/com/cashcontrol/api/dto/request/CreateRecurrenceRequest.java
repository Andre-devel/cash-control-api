package com.cashcontrol.api.dto.request;

import com.cashcontrol.api.domain.entity.RecurrenceFrequency;
import com.cashcontrol.api.domain.entity.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateRecurrenceRequest(
        @NotNull UUID accountId,
        @NotNull TransactionType type,
        @NotNull @Positive BigDecimal amount,
        @NotBlank @Size(max = 255) String description,
        UUID categoryId,
        UUID subcategoryId,
        @NotNull LocalDate startDate,
        LocalDate endDate,
        @NotNull RecurrenceFrequency frequency
) {}
