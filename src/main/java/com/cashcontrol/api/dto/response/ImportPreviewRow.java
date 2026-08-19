package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
import com.cashcontrol.api.domain.entity.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Lançamento do extrato já classificado, ainda não gravado")
public record ImportPreviewRow(

        @Schema(description = "Número da linha no arquivo enviado (1-based)", example = "42")
        int lineNumber,

        @Schema(description = "Hash da linha de origem. Devolver inalterado na confirmação.")
        String externalRef,

        @Schema(description = "Data do lançamento", example = "2026-08-04")
        LocalDate date,

        @Schema(description = "Descrição lida do extrato", example = "Pix Marketplace")
        String description,

        @Schema(description = "Coluna \"Histórico\" original, para o usuário conferir a classificação",
                example = "Pix enviado")
        String rawHistory,

        @Schema(description = "Valor absoluto. A direção está em type.", example = "144.06")
        BigDecimal amount,

        @Schema(description = "Tipo deduzido do histórico", example = "EXPENSE")
        TransactionType type,

        @Schema(description = "Forma de pagamento deduzida do histórico", example = "PIX")
        PaymentMethodSlug paymentMethod,

        @Schema(description = """
                Identidade do estabelecimento, derivada da descrição. {@code null} quando a \
                descrição não deixa nada identificável. É por ela que "aplicar a todas as \
                linhas deste estabelecimento" agrupa as linhas da prévia.""", example = "padaria sao joao")
        String merchantKey,

        @Schema(description = "Categoria sugerida — por regra do usuário ou pelo histórico do estabelecimento, "
                + "conforme suggestionSource")
        UUID suggestedCategoryId,

        @Schema(description = "Nome da categoria sugerida", example = "Alimentação")
        String suggestedCategoryName,

        @Schema(description = "Subcategoria sugerida, quando a sugestão trouxer uma")
        UUID suggestedSubcategoryId,

        @Schema(description = "Nome da subcategoria sugerida")
        String suggestedSubcategoryName,

        @Schema(description = "De onde veio a sugestão: RULE (regra do usuário), "
                + "HISTORY (categoria mais usada nesse estabelecimento) ou NONE")
        SuggestionSource suggestionSource,

        @Schema(description = "true quando esta linha já foi importada antes nesta conta")
        boolean duplicate,

        @Schema(description = "true quando o histórico não é conhecido e o tipo foi deduzido do sinal do valor")
        boolean unknownHistory
) {}
