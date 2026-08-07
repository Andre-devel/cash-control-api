package com.cashcontrol.api.service.fatura;

import com.cashcontrol.api.dto.response.ImportRowError;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Resultado da leitura de uma fatura em PDF.
 *
 * <p>Como no extrato, uma linha malformada não derruba o arquivo: ela vai para
 * {@code errors} e a leitura continua.
 *
 * @param dueDate     vencimento declarado no cabeçalho. É dele que sai o mês de referência
 *                    da fatura no domínio
 * @param totalAmount total da fatura declarado pelo banco, quando encontrado. Meramente
 *                    informativo — serve para o usuário conferir a prévia contra o PDF
 */
public record ParsedFatura(
        LocalDate dueDate,
        BigDecimal totalAmount,
        List<ParsedCardSection> cardSections,
        List<ImportRowError> errors
) {}
