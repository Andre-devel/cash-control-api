package com.cashcontrol.api.dto.request;

import java.time.LocalDate;

public record PauseRecurrenceRequest(
        LocalDate resumeAt
) {}
