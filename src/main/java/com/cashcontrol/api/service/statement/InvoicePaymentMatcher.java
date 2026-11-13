package com.cashcontrol.api.service.statement;

import com.cashcontrol.api.domain.entity.Invoice;
import com.cashcontrol.api.domain.entity.InvoiceStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Casa pagamentos do extrato com faturas. Uma fatura aceita mais de um pagamento (a paga em
 * partes): cada pagamento casado desconta do que falta pagar nela, e o seguinte só casa com o
 * que sobrou. Fatura já quitada por um pagamento lançado não é candidata; a quitada por
 * "marcar como paga" é, porque o pagamento real dela ainda não existe na conta.
 */
public final class InvoicePaymentMatcher {

    /**
     * Janela, em dias em volta da data do pagamento, em que o vencimento de uma fatura a
     * deixa candidata: paga-se até umas duas semanas antes do vencimento, e às vezes com
     * alguns dias de atraso.
     */
    public static final int DUE_DAYS_BEFORE_PAYMENT = 5;
    public static final int DUE_DAYS_AFTER_PAYMENT = 15;

    private final List<Invoice> candidates;
    /** O que falta pagar em cada fatura depois dos pagamentos já casados nesta leitura. */
    private final Map<UUID, BigDecimal> remaining = new HashMap<>();

    public InvoicePaymentMatcher(List<Invoice> candidates) {
        this.candidates = candidates;
    }

    /** Até 1% do total, e no mínimo R$ 1,00: centavos de IOF e arredondamento. */
    public static BigDecimal tolerance(Invoice invoice) {
        return invoice.getTotalAmount().multiply(new BigDecimal("0.01")).max(BigDecimal.ONE);
    }

    public static boolean amountMatches(Invoice invoice, BigDecimal amount) {
        return invoice.getTotalAmount().subtract(amount).abs().compareTo(tolerance(invoice)) <= 0;
    }

    /**
     * O que falta pagar. Na quitada por "marcar como paga" é o total: o pago ali foi declarado,
     * o dinheiro ainda não saiu da conta.
     */
    public static BigDecimal remainingOf(Invoice invoice) {
        return invoice.isPaidWithoutTransaction()
                ? invoice.getTotalAmount()
                : invoice.getTotalAmount().subtract(invoice.getPaidAmount());
    }

    /**
     * O pagamento cabe na fatura: não passa do que falta mais a tolerância. Na quitada por
     * "marcar como paga" sempre cabe, porque o pagamento só se liga a ela.
     */
    public static boolean fits(Invoice invoice, BigDecimal amount) {
        return invoice.isPaidWithoutTransaction()
               || amount.compareTo(remainingOf(invoice).add(tolerance(invoice))) <= 0;
    }

    /** As que vencem perto da data, para o usuário escolher; não reserva nenhuma. */
    public List<Invoice> optionsFor(LocalDate paymentDate) {
        return eligible(paymentDate)
                .sorted(Comparator.comparing(invoice -> Math.abs(ChronoUnit.DAYS.between(paymentDate, invoice.getDueDate()))))
                .toList();
    }

    /**
     * A fatura que este pagamento paga, inteira ou em parte, descontando dela o valor. Cabe o
     * pagamento que não passa do que falta mais a tolerância (o IOF que o banco cobrou e a
     * fatura do app ainda não tem). Primeiro a que ele quita, depois a de vencimento mais
     * próximo. Nula quando o valor não cabe em nenhuma.
     */
    public Invoice claim(LocalDate paymentDate, BigDecimal amount) {
        Invoice best = eligible(paymentDate)
                .filter(invoice -> left(invoice).signum() > 0)
                .filter(invoice -> amount.compareTo(left(invoice).add(tolerance(invoice))) <= 0)
                .min(Comparator.<Invoice, Boolean>comparing(invoice -> !settles(invoice, amount))
                        .thenComparing(invoice -> Math.abs(ChronoUnit.DAYS.between(paymentDate, invoice.getDueDate()))))
                .orElse(null);
        if (best != null) {
            remaining.put(best.getId(), left(best).subtract(amount));
        }
        return best;
    }

    private boolean settles(Invoice invoice, BigDecimal amount) {
        return left(invoice).subtract(amount).abs().compareTo(tolerance(invoice)) <= 0;
    }

    private BigDecimal left(Invoice invoice) {
        return remaining.computeIfAbsent(invoice.getId(), id -> remainingOf(invoice));
    }

    private Stream<Invoice> eligible(LocalDate paymentDate) {
        return candidates.stream()
                .filter(invoice -> invoice.getStatus() != InvoiceStatus.PAID || invoice.isPaidWithoutTransaction())
                .filter(invoice -> !invoice.getDueDate().isBefore(paymentDate.minusDays(DUE_DAYS_BEFORE_PAYMENT)))
                .filter(invoice -> !invoice.getDueDate().isAfter(paymentDate.plusDays(DUE_DAYS_AFTER_PAYMENT)));
    }
}
