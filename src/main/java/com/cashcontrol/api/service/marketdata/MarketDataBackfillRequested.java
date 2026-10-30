package com.cashcontrol.api.service.marketdata;

import java.time.LocalDate;

/** Uma aplicação precisa de índices anteriores ao histórico carregado, desde {@code from}. */
public record MarketDataBackfillRequested(LocalDate from) {
}
