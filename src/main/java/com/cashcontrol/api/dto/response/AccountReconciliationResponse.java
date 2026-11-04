package com.cashcontrol.api.dto.response;

/**
 * @author Andre
 * 4 de Novembro. de 2026
 */

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Saldo da conta no app ao lado do que o banco informou na última leitura. {@code bankBalance},
 * {@code bankBalanceAt} e {@code difference} são nulos quando a conta não está vinculada a uma
 * conta do banco ou o banco ainda não informou saldo. {@code difference = bankBalance - appBalance}:
 * o valor que o ajuste lançaria.
 */
public record AccountReconciliationResponse(
        BigDecimal appBalance,
        BigDecimal bankBalance,
        Instant bankBalanceAt,
        BigDecimal difference
) {}
