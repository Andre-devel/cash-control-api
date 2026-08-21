package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "Prévia da leitura de um comprovante de pagamento (PIX). Nada foi gravado.")
public record ReceiptPreviewResponse(

        @Schema(description = "Chave de deduplicação: o endToEndId do PIX quando o comprovante o traz, "
                + "senão um hash de data+valor+destinatário. Devolver inalterado no commit.")
        String externalRef,

        @Schema(description = "Valor lido, ou null se não encontrado", example = "150.00")
        BigDecimal amount,

        @Schema(description = "Data lida, ou null se não encontrada", example = "2026-08-15")
        LocalDate date,

        @Schema(description = "Nome de quem recebeu, lido do comprovante", example = "Padaria São João")
        String recipientName,

        @Schema(description = "CPF/CNPJ de quem recebeu, como veio no comprovante (tipicamente mascarado)")
        String recipientDocument,

        @Schema(description = "Instituição de destino, quando lida")
        String institution,

        @Schema(description = "Mensagem que o pagador escreveu na transferência, quando lida")
        String message,

        @Schema(description = "Identidade do estabelecimento, derivada de recipientName. null quando o "
                + "nome não deixa nada identificável.", example = "padaria sao joao")
        String merchantKey,

        @Schema(description = "Como o usuário renomeou este destinatário da última vez, ou null se nunca renomeou",
                example = "Padaria da esquina")
        String suggestedDescription,

        @Schema(description = "Categoria sugerida pelo histórico do destinatário")
        UUID suggestedCategoryId,

        @Schema(description = "Nome da categoria sugerida", example = "Alimentação")
        String suggestedCategoryName,

        @Schema(description = "Subcategoria sugerida, quando a sugestão trouxer uma")
        UUID suggestedSubcategoryId,

        @Schema(description = "Nome da subcategoria sugerida")
        String suggestedSubcategoryName,

        @Schema(description = "De onde veio a sugestão de categoria: RULE, HISTORY ou NONE")
        SuggestionSource suggestionSource,

        @Schema(description = "true quando já existe uma transação com este externalRef nesta conta")
        boolean duplicate,

        @Schema(description = "Id da transação já existente com este externalRef, quando duplicate=true")
        UUID duplicateTransactionId,

        @Schema(description = "Campos que a leitura não conseguiu identificar, para a tela avisar o usuário",
                example = "[\"destinatário\"]")
        List<String> unreadFields,

        @Schema(description = "true quando nada pôde ser lido do arquivo — comprovante em imagem sem OCR "
                + "habilitado, ou PDF escaneado sem camada de texto. A tela cai para preenchimento manual.")
        boolean unreadable
) {}
