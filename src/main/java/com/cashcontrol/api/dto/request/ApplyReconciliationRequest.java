package com.cashcontrol.api.dto.request;

import com.cashcontrol.api.domain.entity.ReconciliationKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record ApplyReconciliationRequest(
        @Schema(description = """
                As propostas aprovadas, como vieram da prévia. O servidor refaz a análise e só \
                aplica as que ainda valem: repetir a chamada não converte nada duas vezes.""")
        @NotEmpty @Size(max = 2000) @Valid List<Item> items
) {
    public record Item(@NotNull ReconciliationKind kind, @NotNull UUID id) {}
}
