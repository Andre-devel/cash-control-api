package com.cashcontrol.api.service.fatura;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Uma linha de lançamento lida da fatura, ainda crua: sem categoria e sem
 * vínculo com cartão — o cartão vem da seção que a contém.
 *
 * @param lineNumber         número da linha no texto extraído do PDF (1-based), para reportar erros
 * @param date               data da compra
 * @param description        descrição como veio no PDF, incluindo o sufixo "(Parcela X de Y)" —
 *                           é assim que o usuário reconhece o lançamento na prévia
 * @param signedAmount       valor com sinal. Positivo é crédito na fatura (pagamento, estorno),
 *                           negativo é despesa
 * @param installmentNumber  número da parcela, quando a descrição declara uma
 * @param totalInstallments  total de parcelas, quando a descrição declara uma
 */
public record ParsedFaturaRow(
        int lineNumber,
        LocalDate date,
        String description,
        BigDecimal signedAmount,
        Integer installmentNumber,
        Integer totalInstallments
) {

    /**
     * Crédito que é o pagamento da fatura anterior ("PAGTO DEBITO AUTOMATICO", "PAGAMENTO
     * FATURA"). Qualquer outro crédito é estorno; se a regra errar, a linha aparece como
     * estorno na prévia e o usuário a desmarca.
     */
    public boolean isInvoicePayment() {
        String lower = description.toLowerCase(Locale.ROOT);
        return signedAmount.signum() > 0 && (lower.contains("pagto") || lower.contains("pagamento"));
    }
}
