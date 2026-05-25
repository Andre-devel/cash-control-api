package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.CardBrand;
import com.cashcontrol.api.domain.entity.CreditCard;
import com.cashcontrol.api.domain.entity.Invoice;
import com.cashcontrol.api.domain.entity.InvoiceItem;
import com.cashcontrol.api.domain.entity.InvoiceStatus;
import com.cashcontrol.api.repository.CreditCardRepository;
import com.cashcontrol.api.repository.InvoiceItemRepository;
import com.cashcontrol.api.repository.InvoiceRepository;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class CreditCardEntityTest {

    @Autowired
    private CreditCardRepository creditCardRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private InvoiceItemRepository invoiceItemRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID testUserId;

    @BeforeEach
    void createTestUser() {
        testUserId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) " +
                "VALUES (?, " +
                "  (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), " +
                "  (SELECT id FROM auth_origins WHERE slug = 'LOCAL'), " +
                "  NOW()) " +
                "RETURNING id",
                UUID.class,
                "credit-card-entity-test-" + UUID.randomUUID() + "@example.com");
    }

    @Test
    void canSaveAndRetrieveCreditCard() {
        CreditCard card = buildCard("My Visa Card", CardBrand.VISA, "5000.00");

        CreditCard saved = creditCardRepository.saveAndFlush(card);

        assertThat(saved.getId()).isNotNull();

        Optional<CreditCard> found = creditCardRepository.findByIdAndUserIdAndDeletedAtIsNull(saved.getId(), testUserId);
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("My Visa Card");
        assertThat(found.get().getBrand()).isEqualTo(CardBrand.VISA);
        assertThat(found.get().getCreditLimit()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(found.get().getClosingDay()).isEqualTo(15);
        assertThat(found.get().getDueDay()).isEqualTo(25);
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    void allCardBrandsAreStoredCorrectly() {
        for (CardBrand brand : CardBrand.values()) {
            CreditCard card = buildCard("Card " + brand + UUID.randomUUID(), brand, "1000.00");
            CreditCard saved = creditCardRepository.save(card);
            creditCardRepository.flush();

            Optional<CreditCard> found = creditCardRepository.findById(saved.getId());
            assertThat(found.get().getBrand()).isEqualTo(brand);
        }
    }

    @Test
    void canCreateInvoiceForCard() {
        CreditCard card = creditCardRepository.save(buildCard("Test Card", CardBrand.MASTERCARD, "3000.00"));

        Invoice invoice = buildInvoice(card, "2026-01", InvoiceStatus.OPEN);
        Invoice savedInvoice = invoiceRepository.saveAndFlush(invoice);

        assertThat(savedInvoice.getId()).isNotNull();

        Optional<Invoice> found = invoiceRepository.findByCreditCard_IdAndReferenceMonth(card.getId(), "2026-01");
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(InvoiceStatus.OPEN);
        assertThat(found.get().getTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    void canLinkInvoiceItemToInvoice() {
        CreditCard card = creditCardRepository.save(buildCard("Items Card", CardBrand.ELO, "2000.00"));
        Invoice invoice = invoiceRepository.save(buildInvoice(card, "2026-02", InvoiceStatus.OPEN));

        InvoiceItem item = new InvoiceItem();
        item.setUserId(testUserId);
        item.setInvoice(invoice);
        item.setDescription("Grocery purchase");
        item.setAmount(new BigDecimal("150.00"));
        item.setCompetenceDate(LocalDate.now());

        InvoiceItem savedItem = invoiceItemRepository.save(item);

        assertThat(savedItem.getId()).isNotNull();

        List<InvoiceItem> items = invoiceItemRepository.findAllByInvoice_IdAndCancelledAtIsNull(invoice.getId());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getDescription()).isEqualTo("Grocery purchase");
        assertThat(items.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("150.00"));
    }

    @Test
    void invoiceCreditCardNavigationWorks() {
        CreditCard card = creditCardRepository.save(buildCard("Nav Card", CardBrand.AMEX, "8000.00"));
        Invoice invoice = invoiceRepository.save(buildInvoice(card, "2026-03", InvoiceStatus.CLOSED));

        Optional<Invoice> found = invoiceRepository.findById(invoice.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getCreditCard().getId()).isEqualTo(card.getId());
    }

    @Test
    void allInvoiceStatusesAreStoredCorrectly() {
        CreditCard card = creditCardRepository.save(buildCard("Status Card", CardBrand.VISA, "5000.00"));
        int month = 1;
        for (InvoiceStatus status : InvoiceStatus.values()) {
            Invoice invoice = buildInvoice(card, "2027-" + String.format("%02d", month++), status);
            Invoice saved = invoiceRepository.save(invoice);
            invoiceRepository.flush();

            Optional<Invoice> found = invoiceRepository.findById(saved.getId());
            assertThat(found.get().getStatus()).isEqualTo(status);
        }
    }

    @Test
    void creditCardScopedToUser() {
        CreditCard card = creditCardRepository.save(buildCard("Scoped Card", CardBrand.VISA, "1000.00"));

        UUID otherId = UUID.randomUUID();
        Optional<CreditCard> notFound = creditCardRepository.findByIdAndUserIdAndDeletedAtIsNull(card.getId(), otherId);
        assertThat(notFound).isEmpty();

        Optional<CreditCard> found = creditCardRepository.findByIdAndUserIdAndDeletedAtIsNull(card.getId(), testUserId);
        assertThat(found).isPresent();
    }

    private CreditCard buildCard(String name, CardBrand brand, String limit) {
        CreditCard card = new CreditCard();
        card.setUserId(testUserId);
        card.setName(name);
        card.setBrand(brand);
        card.setCreditLimit(new BigDecimal(limit));
        card.setClosingDay(15);
        card.setDueDay(25);
        return card;
    }

    private Invoice buildInvoice(CreditCard card, String referenceMonth, InvoiceStatus status) {
        Invoice invoice = new Invoice();
        invoice.setUserId(testUserId);
        invoice.setCreditCard(card);
        invoice.setStatus(status);
        invoice.setReferenceMonth(referenceMonth);
        invoice.setClosingDate(LocalDate.now().withDayOfMonth(15));
        invoice.setDueDate(LocalDate.now().withDayOfMonth(25));
        invoice.setTotalAmount(BigDecimal.ZERO);
        invoice.setPaidAmount(BigDecimal.ZERO);
        return invoice;
    }
}
