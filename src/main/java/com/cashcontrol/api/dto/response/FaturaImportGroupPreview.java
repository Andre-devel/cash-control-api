package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = """
        Uma seção "CARTÃO ****XXXX" da fatura.

        Um PDF do Inter cobre o titular e os adicionais, então a prévia vem agrupada: \
        o cliente escolhe (ou confirma) um cartão cadastrado por grupo antes de confirmar.""")
public record FaturaImportGroupPreview(

        @Schema(description = "Últimos 4 dígitos lidos no cabeçalho da seção", example = "7866")
        String cardLast4,

        @Schema(description = "Cartão cadastrado com esses mesmos 4 dígitos. Null quando nenhum casou "
                              + "— nesse caso o cliente precisa escolher o cartão à mão.")
        UUID suggestedCreditCardId,

        @Schema(description = "Nome do cartão sugerido", example = "Inter Black")
        String suggestedCreditCardName,

        @Schema(description = "Lançamentos desta seção, na ordem do PDF")
        List<FaturaImportPreviewRow> rows
) {}
