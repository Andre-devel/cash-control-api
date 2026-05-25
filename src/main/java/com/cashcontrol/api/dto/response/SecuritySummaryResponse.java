package com.cashcontrol.api.dto.response;

public record SecuritySummaryResponse(
        long lockedAccountsCount,
        long failedAttemptsLast24h,
        long forcedReAuthsLast24h
) {}