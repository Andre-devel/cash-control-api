package com.cashcontrol.api.dto.request;

import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
import com.cashcontrol.api.domain.entity.TransactionStatus;
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

@Schema(description = "Os campos da prévia de comprovante já revisados pelo usuário, prontos para virar transação")
public record ReceiptCommitRequest(

        @Schema(description = "UUID da conta que recebe a transação", required = true)
        @NotNull UUID accountId,

        @Schema(description = "Chave de deduplicação devolvida pela prévia, inalterada", required = true)
        @NotBlank String externalRef,

        @Schema(description = "Tipo do lançamento. EXPENSE para um PIX enviado, INCOME para um recebido.",
                required = true)
        @NotNull TransactionType type,

        @Schema(description = "Valor, já revisado pelo usuário", required = true, example = "150.00")
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,

        @Schema(description = "Descrição final do lançamento — o apelido do destinatário, editado ou não",
                required = true, example = "Padaria São João")
        @NotBlank @Size(max = 255) String description,

        @Schema(description = """
                O nome do destinatário como o comprovante trouxe, antes de qualquer edição do \
                usuário. É dele que se deriva o merchantKey para lembrar o apelido — usar a \
                descrição editada seria circular, o apelido é justamente o que não se repete \
                no próximo comprovante do mesmo destinatário.""", required = true)
        @NotBlank String originalDescription,

        @Schema(description = "Data do lançamento", required = true, example = "2026-08-15")
        @NotNull LocalDate competenceDate,

        @Schema(description = "Categoria escolhida na revisão")
        UUID categoryId,

        @Schema(description = "Subcategoria escolhida na revisão")
        UUID subcategoryId,

        @Schema(description = "Status do lançamento. Defaults to PAID — um comprovante é sempre um "
                + "pagamento já efetuado.", example = "PAID")
        TransactionStatus status
) {

    public PaymentMethodSlug paymentMethod() {
        return PaymentMethodSlug.PIX;
    }
}
