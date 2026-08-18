package com.cashcontrol.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Lançamento aprovado pelo usuário na prévia, pronto para virar item de fatura")
public record FaturaImportCommitRow(

        @Schema(description = "Número da linha no arquivo original, usado só para reportar erros", example = "59")
        int lineNumber,

        @Schema(description = "Cartão que receberá o lançamento. Vem do grupo escolhido na prévia.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull UUID creditCardId,

        @Schema(description = """
                Os 4 dígitos da seção do PDF de onde a linha saiu, como devolvido pela prévia.

                Faz parte da identidade da linha: é com ele que a chave das parcelas futuras \
                é gerada de forma a bater com o PDF do mês seguinte.""",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "7866")
        @NotBlank @Pattern(regexp = "\\d{4}", message = "Informe exatamente 4 dígitos.") String cardLast4,

        @Schema(description = "Hash devolvido pela prévia. É a chave de deduplicação.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 64) String externalRef,

        @Schema(description = """
                A posição da linha dentro do grupo de linhas idênticas do arquivo, como \
                devolvida pela prévia.

                Faz parte da identidade da linha, junto com o final do cartão: duas compras \
                parceladas no mesmo dia, no mesmo estabelecimento e com o mesmo número de \
                parcelas só se distinguem por ele. Recontá-lo aqui daria outro número, \
                porque a confirmação recebe só as linhas que o usuário marcou.""",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
        @PositiveOrZero int ordinal,

        @Schema(description = "Data da compra", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-04-04")
        @NotNull LocalDate date,

        @Schema(description = "Descrição do lançamento, já com a edição do usuário se houver",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "Fone de ouvido")
        @NotBlank @Size(max = 255) String description,

        @Schema(description = """
                A descrição como o arquivo a trouxe, sem edição.

                Não vai para o lançamento: serve para gerar a chave das parcelas seguintes. \
                Elas precisam nascer com a chave que a linha do PDF do mês que vem vai \
                produzir, e o PDF não sabe nada da descrição que o usuário escolheu.""",
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "SHOPEE *LarkSpComercio (Parcela 04 de 05)")
        @NotBlank @Size(max = 255) String originalDescription,

        @Schema(description = "Valor positivo da despesa", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "55.19")
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,

        @Schema(description = "Número da parcela, quando houver", example = "4")
        @Positive Integer installmentNumber,

        @Schema(description = "Total de parcelas, quando houver", example = "5")
        @Positive Integer totalInstallments,

        @Schema(description = "Categoria escolhida ou aceita da sugestão. Opcional.")
        UUID categoryId
) {}
