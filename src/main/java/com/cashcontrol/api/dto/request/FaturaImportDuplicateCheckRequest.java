package com.cashcontrol.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.UUID;

@Schema(description = """
        Quais linhas da prévia já existem na fatura de um cartão escolhido à mão.

        A prévia só consegue marcar duplicatas dos grupos cujo cartão ela mesma sugeriu \
        (os 4 dígitos do PDF casaram com um cartão cadastrado). Quando o usuário escolhe \
        o cartão de destino no cliente — um cartão virtual, por exemplo — a marcação passa \
        a valer para outra fatura, e é este endpoint que a refaz.""")
public record FaturaImportDuplicateCheckRequest(

        @Schema(description = "Cartão de destino escolhido para a seção do PDF",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull UUID creditCardId,

        @Schema(description = "Mês de referência da fatura, como devolvido pela prévia",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-07")
        @NotNull @Pattern(regexp = "\\d{4}-\\d{2}", message = "Use o formato aaaa-mm.") String referenceMonth,

        @Schema(description = "Os externalRef das linhas da seção, como vieram da prévia",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty List<@NotNull String> externalRefs
) {}
