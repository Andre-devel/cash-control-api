package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.CardBrand;
import com.cashcontrol.api.domain.entity.CreditCard;
import com.cashcontrol.api.domain.entity.Invoice;
import com.cashcontrol.api.domain.entity.InvoiceItem;
import com.cashcontrol.api.domain.entity.InvoiceStatus;
import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateCardRequest;
import com.cashcontrol.api.dto.request.CreateInstallmentRequest;
import com.cashcontrol.api.dto.request.EditCardRequest;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.CreditCardRepository;
import com.cashcontrol.api.repository.InvoiceItemRepository;
import com.cashcontrol.api.repository.InvoiceRepository;
import com.cashcontrol.api.repository.PaymentMethodRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.CreditCardService;
import com.cashcontrol.api.service.InstallmentService;
import com.cashcontrol.api.service.InvoiceCycleCalculator;
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
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Corrigir o ciclo do cartão (fechava no 1 e vencia no 10; o emissor fecha no 30 e vence no
 * 7) refaz as faturas que já existem em vez de deixá-las com as datas do ciclo errado.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class CardCycleChangeIntegrationTest {

    @Autowired private AccountService accountService;
    @Autowired private CreditCardService creditCardService;
    @Autowired private InstallmentService installmentService;
    @Autowired private InvoiceCycleCalculator cycleCalculator;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CreditCardRepository creditCardRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private InvoiceItemRepository invoiceItemRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private PaymentMethodRepository paymentMethodRepository;
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
                "card-cycle-" + UUID.randomUUID() + "@example.com");
        accountId = accountService.createAccount(
                new CreateAccountRequest("Conta Inter", AccountType.CHECKING, "BRL", null, 0, null), userId).id();
        cardId = creditCardService.createCard(
                new CreateCardRequest("Inter", CardBrand.MASTERCARD, "Inter", null,
                        new BigDecimal("10000.00"), 1, 10, null), userId).id();
    }

    @Test
    void importedInvoice_keepsItsItems_andTakesTheNewCycleDates() {
        // Fatura deste mês vista pelo ciclo errado: fechou dia 1, venceu dia 10.
        YearMonth month = YearMonth.now();
        CreditCard card = creditCardRepository.findById(cardId).orElseThrow();
        Invoice invoice = creditCardService.getOrCreateInvoice(card,
                cycleCalculator.calculateForCharge(month.atDay(1), 1, 10));
        InvoiceItem item = importedItem(invoice, month.minusMonths(1).atDay(24));

        editCycle(30, 7);

        Invoice updated = invoiceRepository.findById(invoice.getId()).orElseThrow();
        assertThat(updated.getReferenceMonth()).isEqualTo(cycleCalculator.toReferenceMonth(month));
        assertThat(updated.getClosingDate()).isEqualTo(month.atDay(Math.min(30, month.lengthOfMonth())));
        assertThat(updated.getDueDate()).isEqualTo(month.plusMonths(1).atDay(7));
        // Vencia dia 10 deste mês; agora vence no mês que vem, então não está vencida.
        assertThat(updated.getStatus()).isNotEqualTo(InvoiceStatus.OVERDUE);

        InvoiceItem moved = invoiceItemRepository.findById(item.getId()).orElseThrow();
        assertThat(moved.getInvoice().getId()).isEqualTo(invoice.getId());
        assertThat(moved.getCompetenceDate()).isEqualTo(month.atDay(24));
        assertThat(transactionRepository.findById(moved.getTransaction().getId()).orElseThrow().getCompetenceDate())
                .isEqualTo(month.atDay(24));
        assertThat(cycleCalculator.calculateForCharge(moved.getCompetenceDate(), 30, 7).referenceMonth())
                .isEqualTo(updated.getReferenceMonth());
    }

    @Test
    void manualPurchase_inAnUnpaidInvoice_movesToTheInvoiceItsDateGivesInTheNewCycle() {
        // Compra do dia 15: fechando no 1 ela ia para a fatura do mês seguinte; fechando no 30,
        // fica na do próprio mês.
        YearMonth purchaseMonth = YearMonth.now().plusMonths(2);
        installmentService.createInstallmentSeries(new CreateInstallmentRequest(accountId, new BigDecimal("300.00"), 3,
                purchaseMonth.atDay(15), "Loja", null, null, null, PaymentMethodSlug.CREDIT_CARD, cardId), userId);
        entityManager.flush();
        Transaction first = transactionRepository.findAll().stream()
                .filter(tx -> userId.equals(tx.getUserId()) && Integer.valueOf(1).equals(tx.getInstallmentNumber()))
                .findFirst().orElseThrow();
        assertThat(invoiceItemRepository.findByTransaction_Id(first.getId()).orElseThrow()
                .getInvoice().getReferenceMonth()).isEqualTo(cycleCalculator.toReferenceMonth(purchaseMonth.plusMonths(1)));

        editCycle(30, 7);

        InvoiceItem item = invoiceItemRepository.findByTransaction_Id(first.getId()).orElseThrow();
        assertThat(item.getInvoice().getReferenceMonth()).isEqualTo(cycleCalculator.toReferenceMonth(purchaseMonth));
        assertThat(item.getCompetenceDate()).isEqualTo(purchaseMonth.atDay(15));
    }

    // ── apoio ─────────────────────────────────────────────────────────────────

    private void editCycle(int closingDay, int dueDay) {
        entityManager.flush();
        creditCardService.editCard(cardId, new EditCardRequest("Inter", CardBrand.MASTERCARD, "Inter", null,
                new BigDecimal("10000.00"), closingDay, dueDay), userId);
        entityManager.flush();
        entityManager.clear();
    }

    private InvoiceItem importedItem(Invoice invoice, LocalDate competence) {
        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setAccount(accountRepository.findById(accountId).orElseThrow());
        tx.setType(TransactionType.EXPENSE);
        tx.setStatus(TransactionStatus.PENDING);
        tx.setAmount(new BigDecimal("336.81"));
        tx.setDescription("CP PARC SHOPPING INTER");
        tx.setCompetenceDate(competence);
        tx.setPaymentMethod(paymentMethodRepository.findBySlug(PaymentMethodSlug.CREDIT_CARD).orElseThrow());
        tx.setCreditCard(invoice.getCreditCard());
        tx.setExternalRef("ref-parcela-4");
        transactionRepository.save(tx);

        InvoiceItem item = new InvoiceItem();
        item.setUserId(userId);
        item.setInvoice(invoice);
        item.setDescription(tx.getDescription());
        item.setAmount(tx.getAmount());
        item.setCompetenceDate(competence);
        item.setTransaction(tx);
        item.setExternalRef("ref-parcela-4");
        return invoiceItemRepository.save(item);
    }
}
