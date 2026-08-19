package com.cashcontrol.api.dto.request;

import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
import com.cashcontrol.api.domain.entity.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Lançamento aprovado pelo usuário na prévia, pronto para virar transação")
public record ImportCommitRow(

        @Schema(description = "Número da linha no arquivo original, usado só para reportar erros", example = "42")
        int lineNumber,

        @Schema(description = "Hash devolvido pela prévia. É a chave de deduplicação.", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 64) String externalRef,

        @Schema(description = "Data do lançamento", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-08-04")
        @NotNull LocalDate date,

        @Schema(description = "Descrição da transação", requiredMode = Schema.RequiredMode.REQUIRED, example = "Pix Marketplace")
        @NotBlank @Size(max = 255) String description,

        @Schema(description = """
                A descrição como o arquivo a trouxe, sem edição.

                Não vai para o lançamento: é a identidade do estabelecimento para a memória \
                de apelido (ver MerchantAliasService). Sem ela o servidor só veria o texto \
                renomeado e não teria como saber o que ele substitui. Opcional por \
                compatibilidade — quando ausente, a descrição enviada é tratada como a \
                original, ou seja, como se o usuário não tivesse renomeado nada.""",
                example = "ANTHROPIC* CLAUDE SUB")
        @Size(max = 255) String originalDescription,

        @Schema(description = "Valor positivo. A direção vem de type.", requiredMode = Schema.RequiredMode.REQUIRED, example = "144.06")
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,

        @Schema(description = "INCOME, EXPENSE ou REFUND", requiredMode = Schema.RequiredMode.REQUIRED, example = "EXPENSE")
        @NotNull TransactionType type,

        @Schema(description = "Forma de pagamento", requiredMode = Schema.RequiredMode.REQUIRED, example = "PIX")
        @NotNull PaymentMethodSlug paymentMethod,

        @Schema(description = "Categoria escolhida ou aceita da sugestão. Opcional.")
        UUID categoryId
) {}
