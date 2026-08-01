package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.CardBrand;
import com.cashcontrol.api.domain.entity.InvoiceStatus;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateCardRequest;
import com.cashcontrol.api.dto.request.PayInvoiceRequest;
import com.cashcontrol.api.dto.request.RecordChargeRequest;
import com.cashcontrol.api.dto.response.CreditCardResponse;
import com.cashcontrol.api.dto.response.InvoiceResponse;
import com.cashcontrol.api.repository.InvoiceItemRepository;
import com.cashcontrol.api.repository.InvoiceRepository;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.CreditCardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class PartialPaymentTest {

    @Autowired private CreditCardService creditCardService;
    @Autowired private AccountService accountService;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private InvoiceItemRepository invoiceItemRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID sourceAccountId;

    @BeforeEach
    void setUp() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) " +
                "VALUES (?, " +
                "  (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), " +
                "  (SELECT id FROM auth_origins WHERE slug = 'LOCAL'), " +
                "  NOW()) " +
                "RETURNING id",
                UUID.class,
                "partial-payment-" + UUID.randomUUID() + "@example.com");

        var account = accountService.createAccount(
                new CreateAccountRequest("Payment Account", AccountType.CHECKING, "BRL", null, 0,
                        new BigDecimal("10000.00")),
                userId);
        sourceAccountId = account.id();
    }

    /**
     * Sets up a card + an invoice in CLOSED status with total 500.00.
     */
    private UUID setupClosedInvoice(String cardName, BigDecimal invoiceTotal) {
        CreditCardResponse card = creditCardService.createCard(
                new CreateCardRequest(cardName, CardBrand.VISA, "Bank",
                        new BigDecimal("5000.00"), 15, 10, null),
                userId);

        // Record a charge for the fixed date 2025-01-05 → invoice 2025-01
        LocalDate chargeDate = LocalDate.of(2025, 1, 5);
        creditCardService.recordCharge(card.id(),
                new RecordChargeRequest("Test charge", invoiceTotal, chargeDate, null, null, null),
                userId);

        // Manually close the invoice so it can be paid
        var invoice = invoiceRepository.findByCreditCard_IdAndReferenceMonth(card.id(), "2025-01").orElseThrow();
        invoice.setStatus(InvoiceStatus.CLOSED);
        invoiceRepository.save(invoice);
        return invoice.getId();
    }

    @Test
    void fullPayment_setsStatusToPaid_noRevolvingItem() {
        UUID invoiceId = setupClosedInvoice("Full Pay Card", new BigDecimal("500.00"));

        PayInvoiceRequest request = new PayInvoiceRequest(
                new BigDecimal("500.00"), sourceAccountId, null);

        InvoiceResponse response = creditCardService.payInvoice(invoiceId, request, userId);

        assertThat(response.status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(response.paidAmount()).isEqualByComparingTo("500.00");

        // No revolving items on any next invoice
        var allItems = invoiceItemRepository.findAll();
        long revolvingCount = allItems.stream()
                .filter(item -> item.isRevolving() && item.getCancelledAt() == null)
                .count();
        assertThat(revolvingCount).isZero();
    }

    @Test
    void partialPayment_setsStatusToPartial_createsRevolvingItemOnNextInvoice() {
        UUID invoiceId = setupClosedInvoice("Partial Pay Card", new BigDecimal("500.00"));

        // Pay only 200 out of 500 → remaining 300 should become revolving
        PayInvoiceRequest request = new PayInvoiceRequest(
                new BigDecimal("200.00"), sourceAccountId, null);

        InvoiceResponse response = creditCardService.payInvoice(invoiceId, request, userId);

        assertThat(response.status()).isEqualTo(InvoiceStatus.PARTIAL);
        assertThat(response.paidAmount()).isEqualByComparingTo("200.00");

        // Revolving item should exist on the next invoice
        var allItems = invoiceItemRepository.findAll();
        var revolvingItems = allItems.stream()
                .filter(item -> item.isRevolving() && item.getCancelledAt() == null)
                .toList();
        assertThat(revolvingItems).hasSize(1);
        assertThat(revolvingItems.get(0).getAmount()).isEqualByComparingTo("300.00");
        assertThat(revolvingItems.get(0).getDescription()).contains("Revolving balance from");

        // Next invoice should have totalAmount = 300
        var nextInvoice = revolvingItems.get(0).getInvoice();
        assertThat(nextInvoice.getReferenceMonth()).isEqualTo("2025-02");
        assertThat(nextInvoice.getTotalAmount()).isEqualByComparingTo("300.00");
    }

    @Test
    void partialPayment_isAtomic_bothInvoicesUpdatedInSameTransaction() {
        UUID invoiceId = setupClosedInvoice("Atomic Card", new BigDecimal("1000.00"));

        // Pay 400, leaving 600 revolving
        PayInvoiceRequest request = new PayInvoiceRequest(
                new BigDecimal("400.00"), sourceAccountId, null);

        creditCardService.payInvoice(invoiceId, request, userId);

        var paidInvoice = invoiceRepository.findById(invoiceId).orElseThrow();
        assertThat(paidInvoice.getStatus()).isEqualTo(InvoiceStatus.PARTIAL);
        assertThat(paidInvoice.getPaidAmount()).isEqualByComparingTo("400.00");

        // Check next invoice was updated with revolving
        var nextInvoice = invoiceRepository.findByCreditCard_IdAndReferenceMonth(
                paidInvoice.getCreditCard().getId(), "2025-02");
        assertThat(nextInvoice).isPresent();
        assertThat(nextInvoice.get().getTotalAmount()).isEqualByComparingTo("600.00");
    }

    @Test
    void payingOpenInvoice_throwsBusinessRuleException() {
        CreditCardResponse card = creditCardService.createCard(
                new CreateCardRequest("Open Invoice Card", CardBrand.VISA, "Bank",
                        new BigDecimal("5000.00"), 15, 10, null),
                userId);

        LocalDate chargeDate = LocalDate.of(2025, 1, 5);
        creditCardService.recordCharge(card.id(),
                new RecordChargeRequest("Charge", new BigDecimal("100.00"), chargeDate, null, null, null),
                userId);

        // Invoice is OPEN — cannot be paid
        var invoice = invoiceRepository.findByCreditCard_IdAndReferenceMonth(card.id(), "2025-01").orElseThrow();
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.OPEN);

        PayInvoiceRequest request = new PayInvoiceRequest(
                new BigDecimal("100.00"), sourceAccountId, null);
        assertThatThrownBy(() -> creditCardService.payInvoice(invoice.getId(), request, userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("ABERTA");
    }

    @Test
    void payingAlreadyPaidInvoice_throwsBusinessRuleException() {
        UUID invoiceId = setupClosedInvoice("Already Paid Card", new BigDecimal("300.00"));

        // Pay in full
        creditCardService.payInvoice(invoiceId,
                new PayInvoiceRequest(new BigDecimal("300.00"), sourceAccountId, null), userId);

        // Attempt to pay again
        assertThatThrownBy(() -> creditCardService.payInvoice(invoiceId,
                new PayInvoiceRequest(new BigDecimal("100.00"), sourceAccountId, null), userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("já está totalmente paga");
    }
}
