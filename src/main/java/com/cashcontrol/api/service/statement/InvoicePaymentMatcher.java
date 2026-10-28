package com.cashcontrol.api.service.statement;

import com.cashcontrol.api.domain.entity.Invoice;
import com.cashcontrol.api.domain.entity.InvoiceStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Casa pagamentos do extrato com faturas, uma fatura por pagamento. Fatura já quitada por
 * um pagamento lançado não é candidata; a quitada por "marcar como paga" é, porque o
 * pagamento real dela ainda não existe na conta.
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
    private final Set<UUID> claimed = new HashSet<>();

    public InvoicePaymentMatcher(List<Invoice> candidates) {
        this.candidates = candidates;
    }

    /** Até 1% do total, e no mínimo R$ 1,00: centavos de IOF e arredondamento. */
    public static boolean amountMatches(Invoice invoice, BigDecimal amount) {
        BigDecimal tolerance = invoice.getTotalAmount().multiply(new BigDecimal("0.01")).max(BigDecimal.ONE);
        return invoice.getTotalAmount().subtract(amount).abs().compareTo(tolerance) <= 0;
    }

    /** As que vencem perto da data, para o usuário escolher; não reserva nenhuma. */
    public List<Invoice> optionsFor(LocalDate paymentDate) {
        return eligible(paymentDate)
                .sorted(Comparator.comparing(invoice -> Math.abs(ChronoUnit.DAYS.between(paymentDate, invoice.getDueDate()))))
                .toList();
    }

    public Invoice claim(LocalDate paymentDate, BigDecimal amount) {
        Invoice best = eligible(paymentDate)
                .filter(invoice -> !claimed.contains(invoice.getId()))
                // Valor mais próximo primeiro; no empate, o vencimento mais próximo.
                .min(Comparator.<Invoice, BigDecimal>comparing(invoice -> invoice.getTotalAmount().subtract(amount).abs())
                        .thenComparing(invoice -> Math.abs(ChronoUnit.DAYS.between(paymentDate, invoice.getDueDate()))))
                .orElse(null);
        if (best != null) {
            claimed.add(best.getId());
        }
        return best;
    }

    private Stream<Invoice> eligible(LocalDate paymentDate) {
        return candidates.stream()
                .filter(invoice -> invoice.getStatus() != InvoiceStatus.PAID || invoice.isPaidWithoutTransaction())
                .filter(invoice -> !invoice.getDueDate().isBefore(paymentDate.minusDays(DUE_DAYS_BEFORE_PAYMENT)))
                .filter(invoice -> !invoice.getDueDate().isAfter(paymentDate.plusDays(DUE_DAYS_AFTER_PAYMENT)));
    }
}
