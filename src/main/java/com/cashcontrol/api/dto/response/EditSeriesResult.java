package com.cashcontrol.api.dto.response;

public record EditSeriesResult(
        InstallmentSeriesResponse series,
        int affectedInstallments
) {}
