package com.cashcontrol.api.service.statement;

import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
import com.cashcontrol.api.domain.entity.TransactionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Map;

/**
 * Traduz a coluna "Histórico" do extrato para o par (tipo, forma de pagamento)
 * do domínio.
 *
 * <p>O banco pode inventar um histórico novo a qualquer momento, então um valor
 * desconhecido não é erro: cai no {@link #fallback} pelo sinal do valor e a
 * linha é marcada para o usuário revisar na prévia.
 */
@Component
public class StatementHistoryMapper {

    /**
     * @param unknownHistory true quando o histórico não estava na tabela e o mapeamento
     *                       foi deduzido do sinal do valor
     * @param investment     true quando o histórico é aplicação ou resgate: o import pode
     *                       transformar a linha em transferência para a conta de investimento
     */
    public record Mapping(TransactionType type, PaymentMethodSlug paymentMethod, boolean unknownHistory,
                          boolean investment) {}

    /**
     * Chaves já normalizadas (minúsculas, sem acento, sem espaço nas pontas).
     *
     * <p>Aplicação/Resgate/Renda Fixa são movimentações para investimento e
     * "Pagamento efetuado" cobre também pagamento de fatura de cartão. A tabela
     * os mapeia para despesa ou receita, que é o que o histórico sozinho garante;
     * virar transferência ou pagamento de fatura depende de haver conta de
     * investimento ou fatura que case, e quem decide é o import.
     */
    private static final Map<String, Mapping> BY_HISTORY = Map.ofEntries(
            Map.entry("pix enviado", expense(PaymentMethodSlug.PIX)),
            Map.entry("pix recebido", income(PaymentMethodSlug.PIX)),
            Map.entry("pix enviado devolvido", refund(PaymentMethodSlug.PIX)),
            Map.entry("pix recebido devolvido", expense(PaymentMethodSlug.PIX)),
            Map.entry("compra no debito", expense(PaymentMethodSlug.DEBIT_CARD)),
            Map.entry("compra inter shop", expense(PaymentMethodSlug.OTHER)),
            Map.entry("pagamento efetuado", expense(PaymentMethodSlug.BOLETO)),
            Map.entry("cashback", refund(PaymentMethodSlug.OTHER)),
            Map.entry("aplicacao", investment(TransactionType.EXPENSE)),
            Map.entry("debito renda fixa", investment(TransactionType.EXPENSE)),
            Map.entry("resgate", investment(TransactionType.INCOME)),
            Map.entry("credito renda fixa", investment(TransactionType.INCOME)),
            Map.entry("credito liberado", income(PaymentMethodSlug.BANK_TRANSFER)),
            Map.entry("transferencia recebida", income(PaymentMethodSlug.BANK_TRANSFER)),
            Map.entry("transferencia enviada", expense(PaymentMethodSlug.BANK_TRANSFER))
    );

    public Mapping map(String rawHistory, BigDecimal signedAmount) {
        Mapping known = BY_HISTORY.get(normalize(rawHistory));
        return known != null ? known : fallback(signedAmount);
    }

    /**
     * Sem histórico conhecido, o sinal do valor é a única informação confiável:
     * negativo saiu da conta, positivo entrou.
     */
    private Mapping fallback(BigDecimal signedAmount) {
        TransactionType type = signedAmount.signum() < 0 ? TransactionType.EXPENSE : TransactionType.INCOME;
        return new Mapping(type, PaymentMethodSlug.OTHER, true, false);
    }

    /**
     * Minúsculas, sem acento e sem espaço nas pontas. O acento é removido porque
     * o mesmo extrato aparece ora em UTF-8, ora em ISO-8859-1, e "débito" mal
     * decodificado não pode virar uma linha "desconhecida".
     */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(value.trim().toLowerCase(), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").replaceAll("\\s+", " ");
    }

    private static Mapping expense(PaymentMethodSlug slug) {
        return new Mapping(TransactionType.EXPENSE, slug, false, false);
    }

    private static Mapping income(PaymentMethodSlug slug) {
        return new Mapping(TransactionType.INCOME, slug, false, false);
    }

    private static Mapping investment(TransactionType type) {
        return new Mapping(type, PaymentMethodSlug.BANK_TRANSFER, false, true);
    }

    private static Mapping refund(PaymentMethodSlug slug) {
        return new Mapping(TransactionType.REFUND, slug, false, false);
    }
}
