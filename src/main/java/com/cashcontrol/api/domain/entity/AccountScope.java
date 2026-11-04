package com.cashcontrol.api.domain.entity;

/**
 * @author Andre
 * 04 de Novembro. de 2026
 */

/**
 * Escopo da conta e do cartão: pessoa física ou jurídica. O lançamento herda o escopo do cartão,
 * quando é compra no cartão, ou o da conta.
 */
public enum AccountScope {
    PERSONAL,
    BUSINESS
}
