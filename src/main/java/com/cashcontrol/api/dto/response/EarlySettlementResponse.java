package com.cashcontrol.api.dto.response;

public record EarlySettlementResponse(
        TransactionDetailResponse settlementTransaction,
        int cancelledInstallments
) {}
