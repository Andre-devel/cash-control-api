package com.cashcontrol.api.service.marketdata;

import com.cashcontrol.api.domain.entity.MarketIndex;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Um valor de um índice do SGS: % ao dia útil (CDI, Selic) ou % no mês (IPCA). */
public record IndexRate(MarketIndex index, LocalDate date, BigDecimal value) {
}
