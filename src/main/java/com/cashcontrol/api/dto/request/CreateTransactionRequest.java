package com.cashcontrol.api.dto.request;

import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateTransactionRequest(
        @NotNull UUID accountId,
        @NotNull TransactionType type,
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotBlank @Size(max = 255) String description,
        @NotNull LocalDate competenceDate,
        LocalDate paymentDate,
        @Size(max = 5000) String notes,
        UUID categoryId,
        UUID subcategoryId,
        List<UUID> tagIds,
        @Size(max = 255) String location,
        TransactionStatus status
) {}
