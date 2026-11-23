package com.cashcontrol.api.service.openfinance;

/**
 * @author Andre
 * 23 de Novembro. de 2026
 */

/** CPF/CNPJ como chave de comparação: só os dígitos, porque o banco manda formatado. */
public final class Documents {

    private Documents() {
    }

    /** Os dígitos do documento, ou {@code null} quando não sobra nenhum. */
    public static String digits(String document) {
        if (document == null) {
            return null;
        }
        String digits = document.replaceAll("\\D", "");
        return digits.isEmpty() || digits.length() > 14 ? null : digits;
    }
}
