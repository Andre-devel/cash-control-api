package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.CardBrand;
import com.cashcontrol.api.domain.entity.CreditCard;
import com.cashcontrol.api.domain.entity.InstallmentSeries;
import com.cashcontrol.api.domain.entity.Invoice;
import com.cashcontrol.api.domain.entity.InvoiceStatus;
import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
import com.cashcontrol.api.domain.entity.ReconciliationKind;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.request.ApplyReconciliationRequest;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateCardRequest;
import com.cashcontrol.api.dto.request.CreateInstallmentRequest;
import com.cashcontrol.api.dto.response.ApplyReconciliationResult;
import com.cashcontrol.api.dto.response.ImportReconciliationResponse;
import com.cashcontrol.api.dto.response.ReconciliationProposal;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.CreditCardRepository;
import com.cashcontrol.api.repository.InstallmentSeriesRepository;
import com.cashcontrol.api.repository.InvoiceRepository;
import com.cashcontrol.api.repository.PaymentMethodRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.CreditCardService;
import com.cashcontrol.api.service.ImportReconciliationService;
import com.cashcontrol.api.service.InstallmentService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class ImportReconciliationIntegrationTest {

    @Autowired private ImportReconciliationService reconciliationService;
    @Autowired private AccountService accountService;
    @Autowired private CreditCardService creditCardService;
    @Autowired private InstallmentService installmentService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CreditCardRepository creditCardRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private InstallmentSeriesRepository installmentSeriesRepository;
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
                "reconciliation-" + UUID.randomUUID() + "@example.com");
        accountId = accountService.createAccount(
                new CreateAccountRequest("Conta Inter", AccountType.CHECKING, "BRL", null, 0, null), userId).id();
        cardId = creditCardService.createCard(
                new CreateCardRequest("Inter", CardBrand.MASTERCARD, "Inter", null,
                        new BigDecimal("10000.00"), 10, 17, null), userId).id();
    }

    @Test
    void invoicePaymentAndInvestment_areConvertedOnce() {
        accountService.createAccount(
                new CreateAccountRequest("Investimentos", AccountType.INVESTMENT, "BRL", null, 1, null), userId);
        Invoice invoice = invoiceWithPurchases();
        Transaction payment = importedExpense("ref-fatura", "Pagamento Fatura - FULANO",
                invoice.getTotalAmount(), invoice.getDueDate());
        Transaction application = importedExpense("ref-cdb", "Cdb Porq Obj Banco Inter Sa",
                new BigDecimal("413.00"), LocalDate.of(2026, 4, 13));
        importedExpense("ref-mercado", "Supermercado", new BigDecimal("80.00"), LocalDate.of(2026, 4, 14));

        ImportReconciliationResponse preview = reconciliationService.preview(userId);

        assertThat(preview.investmentAccountMissing()).isFalse();
        assertThat(preview.proposals())
                .extracting(ReconciliationProposal::kind, ReconciliationProposal::id)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(ReconciliationKind.INVOICE_PAYMENT, payment.getId()),
                        org.assertj.core.groups.Tuple.tuple(ReconciliationKind.INVESTMENT, application.getId()));

        ApplyReconciliationRequest approveAll = approve(preview);
        ApplyReconciliationResult result = reconciliationService.apply(approveAll, userId);
        entityManager.flush();
        entityManager.clear();

        assertThat(result.invoicePayments()).isEqualTo(1);
        assertThat(result.investments()).isEqualTo(1);
        List<Transaction> account = transactionRepository
                .findAllByAccount_IdAndUserId(accountId, userId, PageRequest.of(0, 50)).getContent();
        assertThat(account).filteredOn(tx -> "ref-fatura".equals(tx.getExternalRef()))
                .singleElement()
                .satisfies(tx -> assertThat(tx.getType()).isEqualTo(TransactionType.INVOICE_PAYMENT));
        assertThat(account).filteredOn(tx -> "ref-cdb".equals(tx.getExternalRef()))
                .singleElement()
                .satisfies(tx -> assertThat(tx.getType()).isEqualTo(TransactionType.TRANSFER));
        assertThat(invoiceRepository.findById(invoice.getId()).orElseThrow().getStatus()).isEqualTo(InvoiceStatus.PAID);

        // Repetir não converte nada de novo.
        assertThat(reconciliationService.preview(userId).proposals()).isEmpty();
        ApplyReconciliationResult again = reconciliationService.apply(approveAll, userId);
        assertThat(again.invoicePayments() + again.investments()).isZero();
        assertThat(again.skipped()).isEqualTo(2);
    }

    @Test
    void investment_withoutInvestmentAccount_isOnlyFlagged() {
        importedExpense("ref-cdb", "Cdb Porq Obj Banco Inter Sa", new BigDecimal("413.00"), LocalDate.of(2026, 4, 13));

        ImportReconciliationResponse preview = reconciliationService.preview(userId);

        assertThat(preview.investmentAccountMissing()).isTrue();
        assertThat(preview.proposals()).isEmpty();
    }

    @Test
    void purchaseSplitAcrossSeries_isMergedIntoTheOldest() {
        // Fevereiro trouxe a parcela 2; março, importado antes, criou outra série com a 3 a 5.
        InstallmentSeries older = series(LocalDate.of(2026, 2, 4));
        InstallmentSeries newer = series(LocalDate.of(2026, 3, 4));
        installment(older, 2, LocalDate.of(2026, 2, 4));
        installment(newer, 3, LocalDate.of(2026, 3, 4));
        installment(newer, 4, LocalDate.of(2026, 4, 4));
        installment(newer, 5, LocalDate.of(2026, 5, 4));

        ImportReconciliationResponse preview = reconciliationService.preview(userId);
        assertThat(preview.proposals()).singleElement().satisfies(proposal -> {
            assertThat(proposal.kind()).isEqualTo(ReconciliationKind.SERIES_MERGE);
            assertThat(proposal.id()).isEqualTo(older.getId());
        });

        assertThat(reconciliationService.apply(approve(preview), userId).seriesMerged()).isEqualTo(1);
        entityManager.flush();
        entityManager.clear();

        assertThat(installmentSeriesRepository.findById(newer.getId())).isEmpty();
        assertThat(transactionRepository.findAllByInstallmentSeries_Id(older.getId())).hasSize(4);
    }

    // ── apoio ─────────────────────────────────────────────────────────────────

    private ApplyReconciliationRequest approve(ImportReconciliationResponse preview) {
        return new ApplyReconciliationRequest(preview.proposals().stream()
                .map(p -> new ApplyReconciliationRequest.Item(p.kind(), p.id()))
                .toList());
    }

    private Invoice invoiceWithPurchases() {
        installmentService.createInstallmentSeries(new CreateInstallmentRequest(accountId, new BigDecimal("300.00"), 3,
                LocalDate.of(2026, 3, 5), "Loja", null, null, null, PaymentMethodSlug.CREDIT_CARD, cardId), userId);
        entityManager.flush();
        return invoiceRepository.findAllByCreditCard_IdAndUserIdOrderByReferenceMonthDesc(cardId, userId,
                        PageRequest.of(0, 10)).getContent().getLast();
    }

    private Transaction importedExpense(String externalRef, String description, BigDecimal amount, LocalDate date) {
        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setAccount(accountRepository.findById(accountId).orElseThrow());
        tx.setType(TransactionType.EXPENSE);
        tx.setStatus(TransactionStatus.PAID);
        tx.setAmount(amount);
        tx.setDescription(description);
        tx.setCompetenceDate(date);
        tx.setPaymentDate(date);
        tx.setPaymentMethod(paymentMethodRepository.findBySlug(PaymentMethodSlug.BOLETO).orElseThrow());
        tx.setExternalRef(externalRef);
        return transactionRepository.save(tx);
    }

    private InstallmentSeries series(LocalDate first) {
        CreditCard card = creditCardRepository.findById(cardId).orElseThrow();
        InstallmentSeries series = new InstallmentSeries();
        series.setUserId(userId);
        series.setAccount(accountRepository.findById(accountId).orElseThrow());
        series.setType(TransactionType.EXPENSE);
        series.setDescription("AZUL WE*LLM9TWV");
        series.setTotalAmount(new BigDecimal("55.19"));
        series.setTotalInstallments(5);
        series.setFirstPaymentDate(first);
        series.setPaymentMethod(paymentMethodRepository.findBySlug(PaymentMethodSlug.CREDIT_CARD).orElseThrow());
        series.setCreditCard(card);
        return installmentSeriesRepository.save(series);
    }

    private void installment(InstallmentSeries series, int number, LocalDate competence) {
        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setAccount(series.getAccount());
        tx.setType(TransactionType.EXPENSE);
        tx.setStatus(TransactionStatus.PENDING);
        tx.setAmount(new BigDecimal("55.19"));
        tx.setDescription(series.getDescription());
        tx.setCompetenceDate(competence);
        tx.setPaymentMethod(series.getPaymentMethod());
        tx.setCreditCard(series.getCreditCard());
        tx.setInstallmentSeries(series);
        tx.setInstallmentNumber(number);
        tx.setTotalInstallments(5);
        transactionRepository.save(tx);
    }
}
