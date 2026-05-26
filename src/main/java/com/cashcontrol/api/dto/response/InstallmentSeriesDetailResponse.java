package com.cashcontrol.api.dto.response;

import java.util.List;

public record InstallmentSeriesDetailResponse(
        InstallmentSeriesResponse series,
        List<TransactionSummaryResponse> installments
) {}
