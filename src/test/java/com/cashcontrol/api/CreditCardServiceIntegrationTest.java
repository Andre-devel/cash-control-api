package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.CardBrand;
import com.cashcontrol.api.domain.entity.InvoiceStatus;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ConflictException;
import com.cashcontrol.api.dto.request.CreateCardRequest;
import com.cashcontrol.api.dto.request.EditCardRequest;
import com.cashcontrol.api.dto.request.RecordChargeRequest;
import com.cashcontrol.api.dto.response.CreditCardResponse;
import com.cashcontrol.api.dto.response.InvoiceItemResponse;
import com.cashcontrol.api.dto.response.InvoiceResponse;
import com.cashcontrol.api.dto.response.LimitUsageResponse;
import com.cashcontrol.api.repository.InvoiceRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class CreditCardServiceIntegrationTest {

    @Autowired private CreditCardService creditCardService;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID userId;

    @BeforeEach
    void createTestUser() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) " +
                "VALUES (?, " +
                "  (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), " +
                "  (SELECT id FROM auth_origins WHERE slug = 'LOCAL'), " +
                "  NOW()) " +
                "RETURNING id",
                UUID.class,
                "cc-integration-" + UUID.randomUUID() + "@example.com");
    }

    private CreateCardRequest defaultRequest(String name) {
        return new CreateCardRequest(name, CardBrand.VISA, "Test Bank",
                new BigDecimal("5000.00"), 15, 10, null);
    }

    @Test
    void createCard_createsCardAndFirstOpenInvoice() {
        CreditCardResponse card = creditCardService.createCard(defaultRequest("My Visa"), userId);

        assertThat(card.id()).isNotNull();
        assertThat(card.name()).isEqualTo("My Visa");
        assertThat(card.brand()).isEqualTo(CardBrand.VISA);
        assertThat(card.creditLimit()).isEqualByComparingTo("5000.00");
        assertThat(card.closingDay()).isEqualTo(15);
        assertThat(card.dueDay()).isEqualTo(10);

        // First invoice should have been created automatically
        var invoices = invoiceRepository.findAll().stream()
                .filter(inv -> inv.getCreditCard().getId().equals(card.id()))
                .toList();
        assertThat(invoices).hasSize(1);
        assertThat(invoices.get(0).getStatus()).isEqualTo(InvoiceStatus.OPEN);
        assertThat(invoices.get(0).getTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void createCard_duplicateName_throwsConflict() {
        creditCardService.createCard(defaultRequest("Duplicate Card"), userId);

        assertThatThrownBy(() -> creditCardService.createCard(defaultRequest("Duplicate Card"), userId))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void listCards_returnsAllCards() {
        creditCardService.createCard(defaultRequest("Card A"), userId);
        creditCardService.createCard(new CreateCardRequest("Card B", CardBrand.MASTERCARD,
                "Bank B", new BigDecimal("3000.00"), 20, 5, null), userId);

        List<CreditCardResponse> cards = creditCardService.listCards(userId);
        assertThat(cards).hasSize(2);
    }

    @Test
    void editCard_changesName() {
        CreditCardResponse created = creditCardService.createCard(defaultRequest("Old Name"), userId);

        EditCardRequest editRequest = new EditCardRequest("New Name", CardBrand.VISA, "Test Bank",
                new BigDecimal("5000.00"), 15, 10);
        CreditCardResponse updated = creditCardService.editCard(created.id(), editRequest, userId);

        assertThat(updated.name()).isEqualTo("New Name");
        assertThat(updated.id()).isEqualTo(created.id());
    }

    @Test
    void editCard_duplicateNameForOtherCard_throwsConflict() {
        creditCardService.createCard(defaultRequest("Card X"), userId);
        CreditCardResponse card2 = creditCardService.createCard(
                new CreateCardRequest("Card Y", CardBrand.ELO, "Bank", new BigDecimal("2000.00"), 10, 5, null),
                userId);

        EditCardRequest editRequest = new EditCardRequest("Card X", CardBrand.ELO, "Bank",
                new BigDecimal("2000.00"), 10, 5);
        assertThatThrownBy(() -> creditCardService.editCard(card2.id(), editRequest, userId))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void archiveCard_setsArchivedAt() {
        CreditCardResponse created = creditCardService.createCard(defaultRequest("Archive Me"), userId);
        assertThat(created.archivedAt()).isNull();

        CreditCardResponse archived = creditCardService.archiveCard(created.id(), userId);
        assertThat(archived.archivedAt()).isNotNull();
    }

    @Test
    void archiveCard_alreadyArchived_throwsBusinessRuleException() {
        CreditCardResponse created = creditCardService.createCard(defaultRequest("Double Archive"), userId);
        creditCardService.archiveCard(created.id(), userId);

        assertThatThrownBy(() -> creditCardService.archiveCard(created.id(), userId))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void recordCharge_assignsToCorrectInvoice_andUpdatesTotalAmount() {
        CreditCardResponse card = creditCardService.createCard(defaultRequest("Charge Card"), userId);

        // closingDay=15, so day 10 belongs to current month
        LocalDate chargeDate = LocalDate.now().withDayOfMonth(1); // day 1 <= 15 → current month
        RecordChargeRequest chargeRequest = new RecordChargeRequest(
                "Supermarket", new BigDecimal("150.00"), chargeDate, null, null, null);

        InvoiceItemResponse item = creditCardService.recordCharge(card.id(), chargeRequest, userId);

        assertThat(item.id()).isNotNull();
        assertThat(item.amount()).isEqualByComparingTo("150.00");
        assertThat(item.description()).isEqualTo("Supermarket");

        // Invoice total should reflect the charge
        var invoices = invoiceRepository.findAll().stream()
                .filter(inv -> inv.getCreditCard().getId().equals(card.id()))
                .filter(inv -> inv.getTotalAmount().compareTo(BigDecimal.ZERO) > 0)
                .toList();
        assertThat(invoices).hasSize(1);
        assertThat(invoices.get(0).getTotalAmount()).isEqualByComparingTo("150.00");
    }

    @Test
    void recordCharge_postClosingDay_goesToNextInvoice() {
        // Create a card with closingDay=15
        CreditCardResponse card = creditCardService.createCard(defaultRequest("Next Month Card"), userId);

        // Force a charge on day 20 (after closing) → should go to NEXT month's invoice
        // Use a fixed date to avoid month-boundary issues
        LocalDate chargeDate = LocalDate.of(2025, 3, 20); // day 20 > 15 → next month = 2025-04
        RecordChargeRequest chargeRequest = new RecordChargeRequest(
                "Post-closing charge", new BigDecimal("200.00"), chargeDate, null, null, null);

        InvoiceItemResponse item = creditCardService.recordCharge(card.id(), chargeRequest, userId);
        assertThat(item.id()).isNotNull();

        // Invoice for 2025-04 should contain this charge
        var nextInvoice = invoiceRepository.findByCreditCard_IdAndReferenceMonth(card.id(), "2025-04");
        assertThat(nextInvoice).isPresent();
        assertThat(nextInvoice.get().getTotalAmount()).isEqualByComparingTo("200.00");
    }

    @Test
    void recordCharge_archivedCard_throwsBusinessRuleException() {
        CreditCardResponse card = creditCardService.createCard(defaultRequest("Archived Card"), userId);
        creditCardService.archiveCard(card.id(), userId);

        RecordChargeRequest chargeRequest = new RecordChargeRequest(
                "Should fail", new BigDecimal("100.00"), LocalDate.now(), null, null, null);

        assertThatThrownBy(() -> creditCardService.recordCharge(card.id(), chargeRequest, userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("arquivad");
    }

    @Test
    void getLimitUsage_computesCorrectly() {
        CreditCardResponse card = creditCardService.createCard(defaultRequest("Limit Test Card"), userId);

        // Record two charges: 100 + 200 = 300 used
        LocalDate chargeDate = LocalDate.of(2025, 1, 5); // day 5 <= 15 → current invoice
        creditCardService.recordCharge(card.id(),
                new RecordChargeRequest("Charge 1", new BigDecimal("100.00"), chargeDate, null, null, null),
                userId);
        creditCardService.recordCharge(card.id(),
                new RecordChargeRequest("Charge 2", new BigDecimal("200.00"), chargeDate, null, null, null),
                userId);

        LimitUsageResponse limitUsage = creditCardService.getLimitUsage(card.id(), userId);

        assertThat(limitUsage.creditLimit()).isEqualByComparingTo("5000.00");
        assertThat(limitUsage.usedLimit()).isEqualByComparingTo("300.00");
        assertThat(limitUsage.availableLimit()).isEqualByComparingTo("4700.00");
    }

    @Test
    void getInvoice_returnsInvoiceWithItems() {
        CreditCardResponse card = creditCardService.createCard(defaultRequest("Invoice Get Card"), userId);

        LocalDate chargeDate = LocalDate.of(2025, 2, 5); // → invoice 2025-02
        creditCardService.recordCharge(card.id(),
                new RecordChargeRequest("Item 1", new BigDecimal("50.00"), chargeDate, null, null, null),
                userId);
        creditCardService.recordCharge(card.id(),
                new RecordChargeRequest("Item 2", new BigDecimal("75.00"), chargeDate, null, null, null),
                userId);

        InvoiceResponse invoice = creditCardService.getInvoice(card.id(), "2025-02", userId, 0, 20);

        assertThat(invoice.referenceMonth()).isEqualTo("2025-02");
        assertThat(invoice.totalAmount()).isEqualByComparingTo("125.00");
        assertThat(invoice.totalItems()).isEqualTo(2);
        assertThat(invoice.items()).hasSize(2);
    }
}
