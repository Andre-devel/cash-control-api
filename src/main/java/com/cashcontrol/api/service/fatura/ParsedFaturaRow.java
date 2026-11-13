package com.cashcontrol.api.service.fatura;

import com.cashcontrol.api.service.MerchantKey;

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
     * A linha com a parcela que o estabelecimento escreve no nome ({@code TOKIO MARINE
     * AUTO04D10}, a 4 de 10), quando ela não declara outra. Cada mês é uma cobrança à vista,
     * com a data dela; a data da compra, que a parcela precisa repetir mês a mês para casar com
     * a gerada no mês anterior, vira o dia 1 do mês da primeira parcela.
     */
    public ParsedFaturaRow withMerchantInstallment() {
        if (installmentNumber != null || signedAmount.signum() > 0) {
            return this;
        }
        MerchantKey.Installment installment = MerchantKey.merchantInstallment(description);
        if (installment == null) {
            return this;
        }
        LocalDate purchaseMonth = date.minusMonths(installment.number() - 1L).withDayOfMonth(1);
        return new ParsedFaturaRow(lineNumber, purchaseMonth, description, signedAmount,
                installment.number(), installment.total());
    }

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
