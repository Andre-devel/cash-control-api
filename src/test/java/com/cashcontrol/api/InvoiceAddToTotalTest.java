package com.cashcontrol.api;

import com.cashcontrol.api.domain.entity.Invoice;
import com.cashcontrol.api.domain.entity.InvoiceStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceAddToTotalTest {

    private static Invoice paid(String total, boolean withoutTransaction) {
        Invoice invoice = new Invoice();
        invoice.setTotalAmount(new BigDecimal(total));
        invoice.setPaidAmount(new BigDecimal(total));
        invoice.setStatus(InvoiceStatus.PAID);
        invoice.setPaidWithoutTransaction(withoutTransaction);
        invoice.setClosingDate(LocalDate.now().plusDays(5));
        invoice.setDueDate(LocalDate.now().plusDays(12));
        return invoice;
    }

    @Test
    void openInvoiceOnlyChangesTotal() {
        Invoice invoice = new Invoice();
        invoice.addToTotal(new BigDecimal("50.00"));

        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("50.00");
        assertThat(invoice.getPaidAmount()).isEqualByComparingTo("0");
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.OPEN);
    }

    @Test
    void settledWithoutTransactionKeepsFollowingTheTotal() {
        Invoice invoice = paid("100.00", true);
        invoice.addToTotal(new BigDecimal("50.00"));

        assertThat(invoice.getPaidAmount()).isEqualByComparingTo("150.00");
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    void paidByRealPaymentBecomesPartialWhenItOwesAgain() {
        Invoice invoice = paid("100.00", false);
        invoice.addToTotal(new BigDecimal("50.00"));

        assertThat(invoice.getPaidAmount()).isEqualByComparingTo("100.00");
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PARTIAL);
        assertThat(invoice.revolvingBalance()).isEqualByComparingTo("50.00");
    }

    @Test
    void partialBecomesPaidWhenTotalDropsToWhatWasPaid() {
        Invoice invoice = paid("100.00", false);
        invoice.addToTotal(new BigDecimal("50.00"));
        invoice.addToTotal(new BigDecimal("-50.00"));

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(invoice.revolvingBalance()).isEqualByComparingTo("0");
    }

    @Test
    void removingEveryPaymentGivesTheStatusTheDatesGive() {
        Invoice invoice = paid("100.00", false);
        invoice.setClosingDate(LocalDate.now().minusDays(20));
        invoice.setDueDate(LocalDate.now().minusDays(10));
        invoice.applyPaidAmount(BigDecimal.ZERO, LocalDate.now());

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.OVERDUE);
    }

    @Test
    void timelineStatusFollowsClosingAndDueDates() {
        Invoice invoice = new Invoice();
        LocalDate today = LocalDate.of(2026, 3, 16);
        invoice.setClosingDate(LocalDate.of(2026, 3, 15));
        invoice.setDueDate(LocalDate.of(2026, 3, 25));

        invoice.refreshTimelineStatus(LocalDate.of(2026, 3, 15));
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.OPEN);
        invoice.refreshTimelineStatus(today);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.CLOSED);
        invoice.refreshTimelineStatus(LocalDate.of(2026, 3, 26));
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.OVERDUE);

        invoice.setStatus(InvoiceStatus.PAID);
        invoice.refreshTimelineStatus(LocalDate.of(2026, 4, 30));
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    void paidByRealPaymentStaysPaidWhenTotalDrops() {
        Invoice invoice = paid("100.00", false);
        invoice.addToTotal(new BigDecimal("-30.00"));

        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("70.00");
        assertThat(invoice.getPaidAmount()).isEqualByComparingTo("100.00");
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
    }
}
