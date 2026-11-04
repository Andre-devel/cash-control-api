package com.cashcontrol.api.dto.request;

import com.cashcontrol.api.domain.entity.AccountScope;
import com.cashcontrol.api.domain.entity.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record EditAccountRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull AccountType type,
        @Size(min = 3, max = 3) String currencyCode,
        @Size(max = 255) String description,
        Integer sortOrder,
        AccountScope scope
) {

    /** Sem escopo: a edição mantém o escopo atual da conta. */
    public EditAccountRequest(String name, AccountType type, String currencyCode, String description,
                              Integer sortOrder) {
        this(name, type, currencyCode, description, sortOrder, null);
    }
}
