package com.cashcontrol.api.dto.request;

import java.time.LocalDate;

public record MarkAsPaidRequest(LocalDate paymentDate) {}
