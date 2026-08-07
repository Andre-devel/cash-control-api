package com.cashcontrol.api.dto.request;

import com.cashcontrol.api.domain.entity.InvoiceImportFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.UUID;

@Schema(description = """
        Confirmação de uma importação de fatura: as linhas que o usuário aprovou na prévia.

        A prévia não guarda estado no servidor — o cliente devolve aqui o que quer importar, \
        já com o cartão escolhido em cada linha. Cada linha é validada de novo (cartão e \
        categoria pertencem ao usuário) e as que já existirem na fatura do mês são ignoradas \
        pelo externalRef.""")
public record FaturaImportCommitRequest(

        @Schema(description = "Formato da fatura de origem", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "INTER_FATURA_PDF")
        @NotNull InvoiceImportFormat format,

        @Schema(description = """
                Mês de referência da fatura, como devolvido pela prévia.

                É a competência do documento, não a data de cada linha: uma parcela comprada \
                em abril entra na fatura de julho, e é na de julho que ela precisa cair.""",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-07")
        @NotNull @Pattern(regexp = "\\d{4}-\\d{2}", message = "Use o formato aaaa-mm.") String referenceMonth,

        @Schema(description = """
                Conta em que as transações de cartão serão lançadas.

                O PDF da fatura não diz nada sobre conta, e uma transação exige uma. É uma \
                escolha do usuário para o arquivo inteiro, mesmo quando ele traz mais de um \
                cartão — a conta só volta a importar quando a fatura for paga.""",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull UUID accountId,

        @Schema(description = "Lançamentos aprovados", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty @Valid List<FaturaImportCommitRow> rows
) {}
