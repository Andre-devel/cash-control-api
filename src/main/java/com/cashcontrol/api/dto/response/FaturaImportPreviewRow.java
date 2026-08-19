package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Lançamento lido da fatura, ainda não gravado")
public record FaturaImportPreviewRow(

        @Schema(description = "Número da linha no texto extraído do PDF (1-based)", example = "59")
        int lineNumber,

        @Schema(description = "Hash da linha de origem. Devolver inalterado na confirmação.")
        String externalRef,

        @Schema(description = """
                Posição da linha dentro do grupo de linhas idênticas do arquivo (0 para a \
                primeira). Devolver inalterado na confirmação: é com ele que a chave das \
                parcelas futuras é gerada quando duas compras do mesmo dia são \
                indistinguíveis uma da outra.""", example = "0")
        int ordinal,

        @Schema(description = "Data da compra", example = "2026-04-04")
        LocalDate date,

        @Schema(description = "Descrição como veio no PDF, incluindo o sufixo de parcela",
                example = "SHOPEE *LarkSpComercio (Parcela 04 de 05)")
        String description,

        @Schema(description = "Valor absoluto da despesa", example = "55.19")
        BigDecimal amount,

        @Schema(description = "Número da parcela, quando o PDF declara uma", example = "4")
        Integer installmentNumber,

        @Schema(description = "Total de parcelas, quando o PDF declara uma", example = "5")
        Integer totalInstallments,

        @Schema(description = """
                Identidade do estabelecimento, derivada da descrição. {@code null} quando a \
                descrição não deixa nada identificável. É por ela que "aplicar a todas as \
                linhas deste estabelecimento" agrupa as linhas da prévia.""", example = "shopee larkspcomercio")
        String merchantKey,

        @Schema(description = """
                Como o usuário renomeou este estabelecimento da última vez, ou null se \
                nunca renomeou.

                Não substitui `description`, que continua sendo o texto do arquivo: os dois \
                vêm juntos para que a prévia possa pré-preencher o apelido e ainda assim \
                mostrar o original.""", example = "Claude - mensalidade")
        String suggestedDescription,

        @Schema(description = "Categoria sugerida — por regra do usuário ou pelo histórico do estabelecimento, "
                + "conforme suggestionSource")
        UUID suggestedCategoryId,

        @Schema(description = "Nome da categoria sugerida", example = "Compras")
        String suggestedCategoryName,

        @Schema(description = "Subcategoria sugerida, quando a sugestão trouxer uma")
        UUID suggestedSubcategoryId,

        @Schema(description = "Nome da subcategoria sugerida")
        String suggestedSubcategoryName,

        @Schema(description = "De onde veio a sugestão: RULE (regra do usuário), "
                + "HISTORY (categoria mais usada nesse estabelecimento) ou NONE")
        SuggestionSource suggestionSource,

        @Schema(description = "true quando esta linha já foi importada na fatura deste mês")
        boolean duplicate
) {}
