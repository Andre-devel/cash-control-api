package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Correções propostas para lançamentos importados antes das regras atuais")
public record ImportReconciliationResponse(
        @Schema(description = """
                true quando há aplicação ou resgate a converter e nenhuma conta de investimento: \
                essas propostas só aparecem depois que ela existir.""")
        boolean investmentAccountMissing,

        List<ReconciliationProposal> proposals
) {}
