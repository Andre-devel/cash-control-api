package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.CardBrand;
import com.cashcontrol.api.domain.entity.Invoice;
import com.cashcontrol.api.domain.entity.InvoiceItem;
import com.cashcontrol.api.domain.entity.InvoiceStatus;
import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateCardRequest;
import com.cashcontrol.api.dto.request.CreateTransactionRequest;
import com.cashcontrol.api.dto.request.MarkAsPaidRequest;
import com.cashcontrol.api.dto.request.PayInvoiceRequest;
import com.cashcontrol.api.dto.response.TransactionDetailResponse;
import com.cashcontrol.api.repository.InvoiceItemRepository;
import com.cashcontrol.api.repository.InvoiceRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.CreditCardService;
import com.cashcontrol.api.service.DashboardService;
import com.cashcontrol.api.service.InvoiceManagementService;
import com.cashcontrol.api.service.TransactionService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Compra no cartão sai da conta uma vez só, pelo pagamento da fatura, e entra no dashboard
 * como despesa quando a fatura é paga.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class CardPurchaseLifecycleIntegrationTest {

    // 2025-01 fecha no dia 15 e já passou: aceita pagamento.
    private static final LocalDate PURCHASE_DATE = LocalDate.of(2025, 1, 5);

    @Autowired private TransactionService transactionService;
    @Autowired private AccountService accountService;
    @Autowired private CreditCardService creditCardService;
    @Autowired private InvoiceManagementService invoiceManagementService;
    @Autowired private DashboardService dashboardService;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private InvoiceItemRepository invoiceItemRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @PersistenceContext private EntityManager entityManager;

    private UUID userId;
    private UUID accountId;
    private UUID cardId;

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
                "card-lifecycle-" + UUID.randomUUID() + "@example.com");
        accountId = accountService.createAccount(new CreateAccountRequest(
                "Conta", AccountType.CHECKING, "BRL", null, null, new BigDecimal("1000.00")), userId).id();
        cardId = creditCardService.createCard(new CreateCardRequest(
                "Cartão", CardBrand.VISA, null, null, new BigDecimal("5000.00"), 15, 10, null), userId).id();
    }

    private TransactionDetailResponse purchase(TransactionStatus status) {
        return transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.EXPENSE, new BigDecimal("100.00"), "Mercado",
                PURCHASE_DATE, PURCHASE_DATE, null, null, null, null, null,
                status, PaymentMethodSlug.CREDIT_CARD, cardId), userId);
    }

    private UUID invoiceId() {
        return invoiceRepository.findByCreditCard_IdAndReferenceMonth(cardId, "2025-01").orElseThrow().getId();
    }

    @Test
    void purchaseIsPendingWithoutPaymentDateAndDoesNotTouchTheBalance() {
        TransactionDetailResponse tx = purchase(null);

        assertThat(tx.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(tx.paymentDate()).isNull();
        assertThat(accountService.computeBalance(accountId, userId)).isEqualByComparingTo("1000.00");
    }

    @Test
    void purchaseCannotBePaidOutsideTheInvoice() {
        assertThatThrownBy(() -> purchase(TransactionStatus.PAID))
                .isInstanceOf(BusinessRuleException.class);

        UUID id = purchase(null).id();
        assertThatThrownBy(() -> transactionService.markAsPaid(id, new MarkAsPaidRequest(null), userId))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void payingTheInvoiceDebitsOnceAndCountsThePurchaseAsExpenseOnce() {
        UUID purchaseId = purchase(null).id();
        LocalDate today = LocalDate.now();

        creditCardService.payInvoice(invoiceId(),
                new PayInvoiceRequest(new BigDecimal("100.00"), accountId, today), userId);

        Transaction paidPurchase = transactionRepository.findById(purchaseId).orElseThrow();
        assertThat(paidPurchase.getStatus()).isEqualTo(TransactionStatus.PAID);
        assertThat(paidPurchase.getPaymentDate()).isEqualTo(today);

        assertThat(transactionRepository.findAll().stream()
                .filter(t -> t.getUserId().equals(userId) && t.getType() == TransactionType.INVOICE_PAYMENT))
                .singleElement()
                .satisfies(payment -> assertThat(payment.getAmount()).isEqualByComparingTo("-100.00"));

        assertThat(accountService.computeBalance(accountId, userId)).isEqualByComparingTo("900.00");
        var overview = dashboardService.getOverviewMetrics(userId);
        assertThat(overview.totalBalance()).isEqualByComparingTo("900.00");
        assertThat(overview.monthlyExpenses()).isEqualByComparingTo("100.00");
    }

    @Test
    void settlingWithoutTransactionMarksPurchasesPaidAndReopeningRevertsThem() {
        UUID purchaseId = purchase(null).id();

        invoiceManagementService.settle(invoiceId(), userId);
        Transaction settled = transactionRepository.findById(purchaseId).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(TransactionStatus.PAID);
        assertThat(settled.getPaymentDate()).isEqualTo(LocalDate.of(2025, 2, 10));
        assertThat(accountService.computeBalance(accountId, userId)).isEqualByComparingTo("1000.00");

        invoiceManagementService.reopen(invoiceId(), userId);
        Transaction reopened = transactionRepository.findById(purchaseId).orElseThrow();
        assertThat(reopened.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(reopened.getPaymentDate()).isNull();
    }

    @Test
    void payingMoreThanTheRemainderIsRejectedInsteadOfTruncated() {
        purchase(null);

        assertThatThrownBy(() -> creditCardService.payInvoice(invoiceId(),
                new PayInvoiceRequest(new BigDecimal("150.00"), accountId, LocalDate.now()), userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("maior que o restante");
    }

    private UUID payInvoice(String amount) {
        creditCardService.payInvoice(invoiceId(),
                new PayInvoiceRequest(new BigDecimal(amount), accountId, LocalDate.now()), userId);
        return transactionRepository.findAll().stream()
                .filter(t -> t.getUserId().equals(userId) && t.getType() == TransactionType.INVOICE_PAYMENT)
                .max(Comparator.comparing(Transaction::getCreatedAt))
                .orElseThrow().getId();
    }

    private Invoice invoice(String referenceMonth) {
        return invoiceRepository.findByCreditCard_IdAndReferenceMonth(cardId, referenceMonth).orElseThrow();
    }

    private List<InvoiceItem> activeRevolvingItems() {
        return invoiceItemRepository.findAllByRevolvingFromInvoice_IdAndCancelledAtIsNullOrderByCreatedAtAsc(invoiceId());
    }

    @Test
    void invoicePaymentCannotBeEditedOrCancelled() {
        purchase(null);
        UUID paymentId = payInvoice("100.00");

        assertThatThrownBy(() -> transactionService.cancelTransaction(paymentId, userId))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void deletingTheInvoicePaymentGivesTheMoneyBackAndReopensTheInvoice() {
        UUID purchaseId = purchase(null).id();
        UUID paymentId = payInvoice("100.00");
        assertThat(accountService.computeBalance(accountId, userId)).isEqualByComparingTo("900.00");

        transactionService.deleteTransaction(paymentId, userId);

        assertThat(accountService.computeBalance(accountId, userId)).isEqualByComparingTo("1000.00");
        Invoice reverted = invoice("2025-01");
        assertThat(reverted.getPaidAmount()).isEqualByComparingTo("0");
        assertThat(reverted.getStatus()).isEqualTo(InvoiceStatus.OVERDUE);
        Transaction reopened = transactionRepository.findById(purchaseId).orElseThrow();
        assertThat(reopened.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(reopened.getPaymentDate()).isNull();
    }

    @Test
    void paymentWithoutInvoiceLinkCannotBeDeleted() {
        purchase(null);
        UUID paymentId = payInvoice("100.00");
        jdbcTemplate.update("UPDATE transactions SET paid_invoice_id = NULL WHERE id = ?", paymentId);
        entityManager.clear();

        assertThatThrownBy(() -> transactionService.deleteTransaction(paymentId, userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("anterior ao vínculo");
    }

    @Test
    void revolvingItemFollowsPaymentsAndTotalOfThePartialInvoice() {
        purchase(null);

        UUID firstPayment = payInvoice("40.00");
        assertThat(invoice("2025-01").getStatus()).isEqualTo(InvoiceStatus.PARTIAL);
        assertThat(activeRevolvingItems()).singleElement()
                .satisfies(item -> assertThat(item.getAmount()).isEqualByComparingTo("60.00"));
        assertThat(invoice("2025-02").getTotalAmount()).isEqualByComparingTo("60.00");

        // Segundo pagamento parcial: o rotativo encolhe, não ganha um segundo item.
        UUID secondPayment = payInvoice("20.00");
        assertThat(activeRevolvingItems()).singleElement()
                .satisfies(item -> assertThat(item.getAmount()).isEqualByComparingTo("40.00"));
        assertThat(invoice("2025-02").getTotalAmount()).isEqualByComparingTo("40.00");

        // Compra nova na fatura parcial: o restante cresce junto.
        purchase(null);
        assertThat(invoice("2025-01").getStatus()).isEqualTo(InvoiceStatus.PARTIAL);
        assertThat(invoice("2025-02").getTotalAmount()).isEqualByComparingTo("140.00");

        transactionService.deleteTransaction(secondPayment, userId);
        assertThat(invoice("2025-02").getTotalAmount()).isEqualByComparingTo("160.00");

        transactionService.deleteTransaction(firstPayment, userId);
        assertThat(invoice("2025-01").getStatus()).isEqualTo(InvoiceStatus.OVERDUE);
        assertThat(activeRevolvingItems()).isEmpty();
        assertThat(invoice("2025-02").getTotalAmount()).isEqualByComparingTo("0");
    }

    @Test
    void payingTheRestOfAPartialInvoiceClearsTheRevolvingItem() {
        purchase(null);
        payInvoice("40.00");
        payInvoice("60.00");

        assertThat(invoice("2025-01").getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(activeRevolvingItems()).isEmpty();
        assertThat(invoice("2025-02").getTotalAmount()).isEqualByComparingTo("0");
    }

    @Test
    void settlingAPartialInvoiceClearsTheRevolvingItemAndReopeningRestoresIt() {
        purchase(null);
        payInvoice("40.00");

        invoiceManagementService.settle(invoiceId(), userId);
        assertThat(activeRevolvingItems()).isEmpty();

        invoiceManagementService.reopen(invoiceId(), userId);
        Invoice reopened = invoice("2025-01");
        assertThat(reopened.getStatus()).isEqualTo(InvoiceStatus.PARTIAL);
        assertThat(reopened.getPaidAmount()).isEqualByComparingTo("40.00");
        assertThat(invoice("2025-02").getTotalAmount()).isEqualByComparingTo("60.00");
    }

    @Test
    void migrationLinksLegacyPaymentsAndRevolvingItemsByDescription() throws Exception {
        purchase(null);
        UUID paymentId = payInvoice("40.00");
        jdbcTemplate.update("UPDATE transactions SET paid_invoice_id = NULL WHERE id = ?", paymentId);
        jdbcTemplate.update("UPDATE invoice_items SET revolving_from_invoice_id = NULL WHERE user_id = ?", userId);

        String migration = new String(getClass().getResourceAsStream(
                "/db/migration/V31__link_invoice_payments_and_revolving.sql").readAllBytes());
        for (String statement : migration.split(";")) {
            if (statement.strip().replaceAll("(?m)^--.*$", "").strip().startsWith("UPDATE")) {
                jdbcTemplate.update(statement);
            }
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT paid_invoice_id FROM transactions WHERE id = ?", UUID.class, paymentId))
                .isEqualTo(invoiceId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM invoice_items WHERE user_id = ? AND revolving_from_invoice_id = ?",
                Long.class, userId, invoiceId()))
                .isEqualTo(1L);
    }

    @Test
    void archivedCardCanBeUnarchived() {
        creditCardService.archiveCard(cardId, userId);
        assertThat(creditCardService.unarchiveCard(cardId, userId).archivedAt()).isNull();
        assertThatThrownBy(() -> creditCardService.unarchiveCard(cardId, userId))
                .isInstanceOf(BusinessRuleException.class);
    }
}
