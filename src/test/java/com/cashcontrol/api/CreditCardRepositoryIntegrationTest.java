package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.CardBrand;
import com.cashcontrol.api.domain.entity.CreditCard;
import com.cashcontrol.api.domain.entity.Invoice;
import com.cashcontrol.api.domain.entity.InvoiceStatus;
import com.cashcontrol.api.repository.CreditCardRepository;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class CreditCardRepositoryIntegrationTest {

    @Autowired private CreditCardRepository creditCardRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID userId;

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
                "ccrepo-" + UUID.randomUUID() + "@example.com");
    }

    @Test
    void findByIdAndUserIdAndDeletedAtIsNull_crossUserReturnsEmpty() {
        CreditCard card = createCard(userId, "My Visa");

        Optional<CreditCard> sameUser = creditCardRepository.findByIdAndUserIdAndDeletedAtIsNull(card.getId(), userId);
        Optional<CreditCard> otherUser = creditCardRepository.findByIdAndUserIdAndDeletedAtIsNull(card.getId(), UUID.randomUUID());

        assertThat(sameUser).isPresent();
        assertThat(otherUser).isEmpty();
    }

    @Test
    void findAllByUserIdAndDeletedAtIsNullAndArchivedAtIsNull_excludesArchivedCards() {
        createCard(userId, "Active Card");
        CreditCard archived = createCard(userId, "Archived Card");
        archived.setArchivedAt(Instant.now());
        creditCardRepository.save(archived);

        List<CreditCard> activeCards = creditCardRepository.findAllByUserIdAndDeletedAtIsNullAndArchivedAtIsNull(userId);

        assertThat(activeCards).hasSize(1);
        assertThat(activeCards.get(0).getName()).isEqualTo("Active Card");
    }

    @Test
    void findByCreditCard_IdAndReferenceMonth_returnsCorrectInvoice() {
        CreditCard card = createCard(userId, "Invoice Test Card");

        Invoice invoiceMarch = createInvoice(card, "2026-03");
        createInvoice(card, "2026-04");

        Optional<Invoice> result = invoiceRepository.findByCreditCard_IdAndReferenceMonth(card.getId(), "2026-03");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(invoiceMarch.getId());
        assertThat(result.get().getReferenceMonth()).isEqualTo("2026-03");
    }

    @Test
    void findAllByUserIdAndDueDateLessThanEqualAndStatusIn_filtersUpcomingInvoicesByStatus() {
        CreditCard card = createCard(userId, "Status Filter Card");

        Invoice openInvoice = createInvoiceWithStatus(card, "2026-01", InvoiceStatus.OPEN,
                LocalDate.of(2026, 1, 10));
        Invoice closedInvoice = createInvoiceWithStatus(card, "2026-02", InvoiceStatus.CLOSED,
                LocalDate.of(2026, 2, 10));
        Invoice paidInvoice = createInvoiceWithStatus(card, "2026-03", InvoiceStatus.PAID,
                LocalDate.of(2026, 3, 10));

        List<Invoice> upcoming = invoiceRepository.findAllByUserIdAndDueDateLessThanEqualAndStatusIn(
                userId,
                LocalDate.of(2026, 12, 31),
                List.of(InvoiceStatus.CLOSED, InvoiceStatus.PARTIAL, InvoiceStatus.OVERDUE));

        assertThat(upcoming).hasSize(1);
        assertThat(upcoming.get(0).getId()).isEqualTo(closedInvoice.getId());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private CreditCard createCard(UUID ownerId, String name) {
        CreditCard card = new CreditCard();
        card.setUserId(ownerId);
        card.setName(name);
        card.setBrand(CardBrand.VISA);
        card.setCreditLimit(new BigDecimal("5000.00"));
        card.setClosingDay(15);
        card.setDueDay(10);
        return creditCardRepository.save(card);
    }

    private Invoice createInvoice(CreditCard card, String referenceMonth) {
        return createInvoiceWithStatus(card, referenceMonth, InvoiceStatus.OPEN,
                LocalDate.of(2026, 12, 10));
    }

    private Invoice createInvoiceWithStatus(CreditCard card, String referenceMonth,
                                             InvoiceStatus status, LocalDate dueDate) {
        Invoice invoice = new Invoice();
        invoice.setUserId(card.getUserId());
        invoice.setCreditCard(card);
        invoice.setReferenceMonth(referenceMonth);
        invoice.setStatus(status);
        invoice.setClosingDate(dueDate.minusDays(5));
        invoice.setDueDate(dueDate);
        invoice.setTotalAmount(BigDecimal.ZERO);
        invoice.setPaidAmount(BigDecimal.ZERO);
        return invoiceRepository.save(invoice);
    }
}
