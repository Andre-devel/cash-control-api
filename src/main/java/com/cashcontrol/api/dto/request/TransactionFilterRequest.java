package com.cashcontrol.api.dto.request;

import com.cashcontrol.api.domain.entity.AccountScope;
import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record TransactionFilterRequest(
        UUID accountId,
        TransactionType type,
        TransactionStatus status,
        UUID categoryId,
        LocalDate competenceDateFrom,
        LocalDate competenceDateTo,
        LocalDate paymentDateFrom,
        LocalDate paymentDateTo,
        BigDecimal amountMin,
        BigDecimal amountMax,
        String searchText,
        boolean includeCancelled,
        PaymentMethodSlug paymentMethod,
        /** Colapsa cada parcelamento em uma linha só, representando a compra inteira. */
        boolean groupInstallments,
        /** Falso tira as compras no cartão de crédito: elas não mexem no saldo da conta. */
        boolean includeCreditCard,
        /** Escopo PF/PJ do lançamento (o do cartão ou o da conta); nulo traz todos. */
        AccountScope scope
) {
    public TransactionFilterRequest(UUID accountId, TransactionType type, TransactionStatus status, UUID categoryId,
                                    LocalDate competenceDateFrom, LocalDate competenceDateTo,
                                    LocalDate paymentDateFrom, LocalDate paymentDateTo,
                                    BigDecimal amountMin, BigDecimal amountMax, String searchText,
                                    boolean includeCancelled, PaymentMethodSlug paymentMethod,
                                    boolean groupInstallments, boolean includeCreditCard) {
        this(accountId, type, status, categoryId, competenceDateFrom, competenceDateTo, paymentDateFrom, paymentDateTo,
                amountMin, amountMax, searchText, includeCancelled, paymentMethod, groupInstallments, includeCreditCard,
                null);
    }

    public static TransactionFilterRequest empty() {
        return new TransactionFilterRequest(null, null, null, null,
                null, null, null, null, null, null, null, false, null, false, true, null);
    }
}
