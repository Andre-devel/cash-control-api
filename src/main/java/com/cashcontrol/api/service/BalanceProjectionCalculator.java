package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.CreditCard;
import com.cashcontrol.api.domain.entity.Invoice;
import com.cashcontrol.api.domain.entity.RecurrenceRule;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.response.BalanceProjectionResponse;
import com.cashcontrol.api.dto.response.BalanceProjectionResponse.Event;
import com.cashcontrol.api.dto.response.BalanceProjectionResponse.EventKind;
import com.cashcontrol.api.dto.response.BalanceProjectionResponse.Point;
import com.cashcontrol.api.repository.InvoiceRepository;
import com.cashcontrol.api.repository.RecurrenceRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Saldo previsto dia a dia a partir do que já está gravado: parte do saldo atual (mesmo recorte
 * de {@code sumNetWorthUpTo}) e aplica, na data prevista, as receitas/despesas pendentes, o
 * restante das faturas e as ocorrências de recorrência que o job ainda não gerou.
 *
 * <p>Gasto que ainda não foi lançado não entra: a projeção não adivinha consumo.
 */
@Component
@RequiredArgsConstructor
public class BalanceProjectionCalculator {

    private final TransactionRepository transactionRepository;
    private final InvoiceRepository invoiceRepository;
    private final RecurrenceRepository recurrenceRepository;
    private final RecurrenceGeneratorService recurrenceGenerator;
    private final InvoiceCycleCalculator invoiceCycleCalculator;

    public BalanceProjectionResponse project(UUID userId, LocalDate today, int days) {
        LocalDate horizon = today.plusDays(days);

        List<Event> events = new ArrayList<>();
        addPendingTransactions(events, userId, today, horizon);
        addUnpaidInvoices(events, userId, today, horizon);
        addFutureRecurrences(events, userId, today, horizon);
        events.sort(Comparator.comparing(Event::date));

        BigDecimal startBalance = orZero(transactionRepository.sumNetWorthUpTo(userId, today));
        List<Point> points = balancePoints(startBalance, events, today, horizon);

        Point lowest = points.getFirst();
        LocalDate firstNegativeDate = null;
        for (Point point : points) {
            if (point.balance().compareTo(lowest.balance()) < 0) {
                lowest = point;
            }
            if (firstNegativeDate == null && point.balance().signum() < 0) {
                firstNegativeDate = point.date();
            }
        }

        return new BalanceProjectionResponse(today, horizon, startBalance, points.getLast().balance(),
                lowest, firstNegativeDate, points, events);
    }

    private void addPendingTransactions(List<Event> events, UUID userId, LocalDate today, LocalDate horizon) {
        for (Transaction tx : transactionRepository.findOpenCashFlowsDueBy(userId, horizon)) {
            LocalDate expected = tx.getPaymentDate() != null ? tx.getPaymentDate() : tx.getCompetenceDate();
            events.add(new Event(notBefore(expected, today), EventKind.TRANSACTION, tx.getDescription(),
                    signed(tx.getType(), tx.getAmount()), false));
        }
    }

    private void addUnpaidInvoices(List<Event> events, UUID userId, LocalDate today, LocalDate horizon) {
        for (Invoice invoice : invoiceRepository.findUnpaidWithCardByUserIdDueBy(userId, horizon)) {
            BigDecimal remaining = invoice.getTotalAmount().subtract(invoice.getPaidAmount());
            if (remaining.signum() <= 0) {
                continue;
            }
            events.add(new Event(notBefore(invoice.getDueDate(), today), EventKind.INVOICE,
                    "Fatura " + invoice.getCreditCard().getName(), remaining.negate(), false));
        }
    }

    /**
     * {@code nextOccurrenceDate} já aponta para depois da última ocorrência gerada, então daqui
     * para frente nada do que se estima existe como transação. No cartão, a ocorrência pesa no
     * vencimento da fatura do ciclo em que cai.
     */
    private void addFutureRecurrences(List<Event> events, UUID userId, LocalDate today, LocalDate horizon) {
        for (RecurrenceRule rule : recurrenceRepository.findActiveByUserIdDueBy(userId, horizon)) {
            if (rule.getType() != TransactionType.INCOME && rule.getType() != TransactionType.EXPENSE
                    && rule.getType() != TransactionType.REFUND) {
                continue;
            }
            CreditCard card = rule.getCreditCard();
            if (card != null && (card.getArchivedAt() != null || card.getDeletedAt() != null)) {
                continue;
            }

            BigDecimal amount = signed(rule.getType(), rule.getAmount());
            LocalDate occurrence = rule.getNextOccurrenceDate();
            while (!occurrence.isAfter(horizon)
                    && (rule.getEndDate() == null || !occurrence.isAfter(rule.getEndDate()))) {
                LocalDate hitsBalance = card == null
                        ? occurrence
                        : invoiceCycleCalculator.calculateForCharge(
                                occurrence, card.getClosingDay(), card.getDueDay()).dueDate();
                if (!hitsBalance.isAfter(horizon)) {
                    events.add(new Event(notBefore(hitsBalance, today), EventKind.RECURRENCE,
                            rule.getDescription(), amount, true));
                }
                occurrence = recurrenceGenerator.nextOccurrence(
                        rule.getStartDate(), occurrence, rule.getFrequency());
            }
        }
    }

    /** Um ponto por dia com evento, além de hoje e do último dia; {@code events} já ordenados. */
    private List<Point> balancePoints(BigDecimal startBalance, List<Event> events,
                                      LocalDate today, LocalDate horizon) {
        List<Point> points = new ArrayList<>();
        BigDecimal balance = startBalance;
        LocalDate current = today;
        for (Event event : events) {
            if (event.date().isAfter(current)) {
                points.add(new Point(current, balance));
                current = event.date();
            }
            balance = balance.add(event.amount());
        }
        points.add(new Point(current, balance));
        if (current.isBefore(horizon)) {
            points.add(new Point(horizon, balance));
        }
        return points;
    }

    private static BigDecimal signed(TransactionType type, BigDecimal amount) {
        return type == TransactionType.EXPENSE ? amount.negate() : amount;
    }

    private static LocalDate notBefore(LocalDate date, LocalDate today) {
        return date.isBefore(today) ? today : date;
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
