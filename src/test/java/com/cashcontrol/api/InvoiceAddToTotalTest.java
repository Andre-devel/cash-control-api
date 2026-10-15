package com.cashcontrol.api;

import com.cashcontrol.api.domain.entity.Invoice;
import com.cashcontrol.api.domain.entity.InvoiceStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceAddToTotalTest {

    private static Invoice paid(String total, boolean withoutTransaction) {
        Invoice invoice = new Invoice();
        invoice.setTotalAmount(new BigDecimal(total));
        invoice.setPaidAmount(new BigDecimal(total));
        invoice.setStatus(InvoiceStatus.PAID);
        invoice.setPaidWithoutTransaction(withoutTransaction);
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
    void paidByRealPaymentReopensWhenItOwesAgain() {
        Invoice invoice = paid("100.00", false);
        invoice.addToTotal(new BigDecimal("50.00"));

        assertThat(invoice.getPaidAmount()).isEqualByComparingTo("100.00");
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.OPEN);
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
