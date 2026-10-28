package com.cashcontrol.api.dto.request;

import com.cashcontrol.api.domain.entity.StatementFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

@Schema(description = """
        Confirmação de uma importação: as linhas que o usuário aprovou na prévia.

        A prévia não guarda estado no servidor — o cliente devolve aqui o que quer importar. \
        Cada linha é validada de novo (conta e categoria pertencem ao usuário, valor positivo) \
        e as que já existirem na conta são ignoradas pelo externalRef.""")
public record ImportCommitRequest(

        @Schema(description = "Conta que receberá as transações", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull UUID accountId,

        @Schema(description = "Formato do extrato de origem", requiredMode = Schema.RequiredMode.REQUIRED, example = "INTER_CSV")
        @NotNull StatementFormat format,

        @Schema(description = "Lançamentos aprovados", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty @Valid List<ImportCommitRow> rows,
        @Schema(description = """
                true para que o saldo atual da conta não mude: o efeito líquido dos lançamentos \
                importados é compensado por um ajuste de saldo de abertura datado na véspera do \
                lançamento mais antigo. É o caso do extrato antigo, cujo resultado já está no \
                saldo informado ao criar a conta.""")
        Boolean keepCurrentBalance
) {}
