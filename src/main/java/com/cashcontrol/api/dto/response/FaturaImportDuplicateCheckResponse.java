package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "As linhas consultadas que já estão na fatura do cartão escolhido.")
public record FaturaImportDuplicateCheckResponse(

        @Schema(description = "Subconjunto dos externalRef enviados que já foram importados")
        List<String> duplicateExternalRefs
) {}
