package com.cashcontrol.api;

import com.cashcontrol.api.domain.entity.CreditCard;
import com.cashcontrol.api.domain.entity.Invoice;
import com.cashcontrol.api.domain.entity.InvoiceStatus;
import com.cashcontrol.api.domain.entity.RecurrenceFrequency;
import com.cashcontrol.api.domain.entity.RecurrenceRule;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.response.BalanceProjectionResponse;
import com.cashcontrol.api.dto.response.BalanceProjectionResponse.Event;
import com.cashcontrol.api.dto.response.BalanceProjectionResponse.EventKind;
import com.cashcontrol.api.dto.response.BalanceProjectionResponse.Point;
import com.cashcontrol.api.repository.InvoiceRepository;
import com.cashcontrol.api.repository.RecurrenceRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.BalanceProjectionCalculator;
import com.cashcontrol.api.service.InvoiceCycleCalculator;
import com.cashcontrol.api.service.RecurrenceGeneratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BalanceProjectionCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);

    @Mock private TransactionRepository transactionRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private RecurrenceRepository recurrenceRepository;

    private BalanceProjectionCalculator calculator;
    private UUID userId;

    @BeforeEach
    void setUp() {
        calculator = new BalanceProjectionCalculator(transactionRepository, invoiceRepository,
                recurrenceRepository, new RecurrenceGeneratorService(), new InvoiceCycleCalculator());
        userId = UUID.randomUUID();
        when(transactionRepository.sumNetWorthUpTo(eq(userId), eq(TODAY))).thenReturn(new BigDecimal("1000.00"));
        when(transactionRepository.findOpenCashFlowsDueBy(eq(userId), any())).thenReturn(List.of());
        when(invoiceRepository.findUnpaidWithCardByUserIdDueBy(eq(userId), any())).thenReturn(List.of());
        when(recurrenceRepository.findActiveByUserIdDueBy(eq(userId), any())).thenReturn(List.of());
    }

    @Test
    void withoutPendingItems_balanceStaysFlatFromTodayToHorizon() {
        BalanceProjectionResponse result = calculator.project(userId, TODAY, 30);

        assertThat(result.from()).isEqualTo(TODAY);
        assertThat(result.to()).isEqualTo(TODAY.plusDays(30));
        assertThat(result.startBalance()).isEqualByComparingTo("1000.00");
        assertThat(result.endBalance()).isEqualByComparingTo("1000.00");
        assertThat(result.points()).extracting(Point::date).containsExactly(TODAY, TODAY.plusDays(30));
        assertThat(result.lowest().date()).isEqualTo(TODAY);
        assertThat(result.firstNegativeDate()).isNull();
        assertThat(result.events()).isEmpty();
    }

    @Test
    void pendingTransactions_useDueDateOrCompetenceAndOverdueCountsToday() {
        when(transactionRepository.findOpenCashFlowsDueBy(userId, TODAY.plusDays(30))).thenReturn(List.of(
                transaction(TransactionType.INCOME, "3000.00", "Salário", TODAY.plusDays(4), null),
                transaction(TransactionType.EXPENSE, "200.00", "Luz", TODAY.minusDays(3), TODAY.minusDays(2)),
                transaction(TransactionType.EXPENSE, "1500.00", "Aluguel", TODAY.minusDays(20), TODAY.plusDays(9))));

        BalanceProjectionResponse result = calculator.project(userId, TODAY, 30);

        assertThat(result.events())
                .extracting(Event::date, Event::description, Event::amount, Event::estimated)
                .containsExactly(
                        tuple(TODAY, "Luz", new BigDecimal("-200.00"), false),
                        tuple(TODAY.plusDays(4), "Salário", new BigDecimal("3000.00"), false),
                        tuple(TODAY.plusDays(9), "Aluguel", new BigDecimal("-1500.00"), false));
        assertThat(result.points())
                .extracting(Point::date, Point::balance)
                .containsExactly(
                        tuple(TODAY, new BigDecimal("800.00")),
                        tuple(TODAY.plusDays(4), new BigDecimal("3800.00")),
                        tuple(TODAY.plusDays(9), new BigDecimal("2300.00")),
                        tuple(TODAY.plusDays(30), new BigDecimal("2300.00")));
        assertThat(result.endBalance()).isEqualByComparingTo("2300.00");
        assertThat(result.lowest().balance()).isEqualByComparingTo("800.00");
    }

    @Test
    void unpaidInvoices_weighTheRemainingAmountOnTheDueDate() {
        CreditCard card = card("Nubank", 10, 20);
        when(invoiceRepository.findUnpaidWithCardByUserIdDueBy(userId, TODAY.plusDays(30))).thenReturn(List.of(
                invoice(card, InvoiceStatus.PARTIAL, "900.00", "400.00", TODAY.plusDays(19)),
                invoice(card, InvoiceStatus.CLOSED, "300.00", "300.00", TODAY.plusDays(5))));

        BalanceProjectionResponse result = calculator.project(userId, TODAY, 30);

        assertThat(result.events())
                .extracting(Event::date, Event::kind, Event::description, Event::amount)
                .containsExactly(tuple(TODAY.plusDays(19), EventKind.INVOICE, "Fatura Nubank", new BigDecimal("-500.00")));
        assertThat(result.endBalance()).isEqualByComparingTo("500.00");
    }

    @Test
    void futureRecurrences_areEstimatedUntilHorizonAndEndDate() {
        RecurrenceRule rent = rule(TransactionType.EXPENSE, "1000.00", "Aluguel",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 11, 5), null);
        RecurrenceRule gym = rule(TransactionType.EXPENSE, "100.00", "Academia",
                LocalDate.of(2026, 1, 15), LocalDate.of(2026, 11, 15), LocalDate.of(2026, 12, 31));
        when(recurrenceRepository.findActiveByUserIdDueBy(userId, TODAY.plusDays(90))).thenReturn(List.of(rent, gym));

        BalanceProjectionResponse result = calculator.project(userId, TODAY, 90);

        assertThat(result.events())
                .extracting(Event::date, Event::description, Event::kind, Event::estimated)
                .containsExactly(
                        tuple(LocalDate.of(2026, 11, 5), "Aluguel", EventKind.RECURRENCE, true),
                        tuple(LocalDate.of(2026, 11, 15), "Academia", EventKind.RECURRENCE, true),
                        tuple(LocalDate.of(2026, 12, 5), "Aluguel", EventKind.RECURRENCE, true),
                        tuple(LocalDate.of(2026, 12, 15), "Academia", EventKind.RECURRENCE, true),
                        tuple(LocalDate.of(2027, 1, 5), "Aluguel", EventKind.RECURRENCE, true));
        assertThat(result.endBalance()).isEqualByComparingTo("-2200.00");
    }

    @Test
    void cardRecurrence_hitsTheBalanceOnTheDueDateOfItsCycle() {
        RecurrenceRule streaming = rule(TransactionType.EXPENSE, "40.00", "Streaming",
                LocalDate.of(2026, 1, 15), LocalDate.of(2026, 11, 15), null);
        streaming.setCreditCard(card("Nubank", 10, 20));
        when(recurrenceRepository.findActiveByUserIdDueBy(userId, TODAY.plusDays(60))).thenReturn(List.of(streaming));

        BalanceProjectionResponse result = calculator.project(userId, TODAY, 60);

        // Compra de 15/11 fecha em 10/12 e vence em 20/12; a de 15/12 só vence em 20/01, fora do horizonte.
        assertThat(result.events())
                .extracting(Event::date, Event::amount)
                .containsExactly(tuple(LocalDate.of(2026, 12, 20), new BigDecimal("-40.00")));
    }

    @Test
    void cardRecurrenceOnArchivedCard_isIgnored() {
        RecurrenceRule streaming = rule(TransactionType.EXPENSE, "40.00", "Streaming",
                LocalDate.of(2026, 1, 15), LocalDate.of(2026, 11, 15), null);
        CreditCard archived = card("Antigo", 10, 20);
        archived.setArchivedAt(Instant.now());
        streaming.setCreditCard(archived);
        when(recurrenceRepository.findActiveByUserIdDueBy(userId, TODAY.plusDays(60))).thenReturn(List.of(streaming));

        assertThat(calculator.project(userId, TODAY, 60).events()).isEmpty();
    }

    @Test
    void goingBelowZero_reportsFirstNegativeDateAndLowestPoint() {
        when(transactionRepository.findOpenCashFlowsDueBy(userId, TODAY.plusDays(30))).thenReturn(List.of(
                transaction(TransactionType.EXPENSE, "1200.00", "Aluguel", TODAY.plusDays(3), null),
                transaction(TransactionType.EXPENSE, "300.00", "Escola", TODAY.plusDays(6), null),
                transaction(TransactionType.INCOME, "5000.00", "Salário", TODAY.plusDays(10), null)));

        BalanceProjectionResponse result = calculator.project(userId, TODAY, 30);

        assertThat(result.firstNegativeDate()).isEqualTo(TODAY.plusDays(3));
        assertThat(result.lowest().date()).isEqualTo(TODAY.plusDays(6));
        assertThat(result.lowest().balance()).isEqualByComparingTo("-500.00");
        assertThat(result.endBalance()).isEqualByComparingTo("4500.00");
    }

    private Transaction transaction(TransactionType type, String amount, String description,
                                    LocalDate competenceDate, LocalDate paymentDate) {
        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setType(type);
        tx.setStatus(TransactionStatus.PENDING);
        tx.setAmount(new BigDecimal(amount));
        tx.setDescription(description);
        tx.setCompetenceDate(competenceDate);
        tx.setPaymentDate(paymentDate);
        return tx;
    }

    private CreditCard card(String name, int closingDay, int dueDay) {
        CreditCard card = new CreditCard();
        card.setUserId(userId);
        card.setName(name);
        card.setClosingDay(closingDay);
        card.setDueDay(dueDay);
        return card;
    }

    private Invoice invoice(CreditCard card, InvoiceStatus status, String total, String paid, LocalDate dueDate) {
        Invoice invoice = new Invoice();
        invoice.setUserId(userId);
        invoice.setCreditCard(card);
        invoice.setStatus(status);
        invoice.setTotalAmount(new BigDecimal(total));
        invoice.setPaidAmount(new BigDecimal(paid));
        invoice.setDueDate(dueDate);
        return invoice;
    }

    private RecurrenceRule rule(TransactionType type, String amount, String description,
                                LocalDate startDate, LocalDate nextOccurrence, LocalDate endDate) {
        RecurrenceRule rule = new RecurrenceRule();
        rule.setUserId(userId);
        rule.setType(type);
        rule.setFrequency(RecurrenceFrequency.MONTHLY);
        rule.setAmount(new BigDecimal(amount));
        rule.setDescription(description);
        rule.setStartDate(startDate);
        rule.setNextOccurrenceDate(nextOccurrence);
        rule.setEndDate(endDate);
        return rule;
    }
}
