package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.CardBrand;
import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
import com.cashcontrol.api.domain.entity.StatementFormat;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateCardRequest;
import com.cashcontrol.api.dto.request.CreateInstallmentRequest;
import com.cashcontrol.api.dto.request.ImportCommitRequest;
import com.cashcontrol.api.dto.request.ImportCommitRow;
import com.cashcontrol.api.dto.response.ImportPreviewResponse;
import com.cashcontrol.api.dto.response.ImportPreviewRow;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.CreditCardService;
import com.cashcontrol.api.service.InstallmentService;
import com.cashcontrol.api.service.StatementImportService;
import com.cashcontrol.api.service.openfinance.PluggyClient;
import com.cashcontrol.api.service.openfinance.PluggyClient.PluggyTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** Prévia do extrato a partir da Pluggy: corte pelo último arquivo e possível duplicata nos dois sentidos. */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@TestPropertySource(properties = {"app.pluggy.client-id=test-client", "app.pluggy.client-secret=test-secret"})
class BankStatementImportIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 6);

    @Autowired private StatementImportService statementImportService;
    @Autowired private AccountService accountService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CreditCardService creditCardService;
    @Autowired private InstallmentService installmentService;
    @MockitoBean private PluggyClient pluggyClient;

    private UUID userId;
    private UUID accountId;
    private String externalAccountId;

    @BeforeEach
    void setUp() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) "
                        + "VALUES (?, (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), "
                        + "(SELECT id FROM auth_origins WHERE slug = 'LOCAL'), NOW()) RETURNING id",
                UUID.class, "bank-import-" + UUID.randomUUID() + "@example.com");
        accountId = accountService.createAccount(
                new CreateAccountRequest("Conta Inter", AccountType.CHECKING, "BRL", null, 0, null), userId).id();
        externalAccountId = UUID.randomUUID().toString();
        UUID connectionId = jdbcTemplate.queryForObject(
                "INSERT INTO bank_connections (user_id, provider, item_id, status) VALUES (?, 'PLUGGY', ?, 'UPDATED') RETURNING id",
                UUID.class, userId, UUID.randomUUID().toString());
        jdbcTemplate.update("INSERT INTO bank_connection_accounts (connection_id, external_account_id, kind, name, number, account_id) "
                + "VALUES (?, ?, 'BANK', 'BANCO INTER', '123456-7', ?)", connectionId, externalAccountId, accountId);
    }

    @Test
    void afterAFileImport_onlyTheLastFileDayOnwardComes_andTheOverlapIsFlagged() {
        commitFile();  // último lançamento do arquivo: 04/08/2026, Pix Marketplace -144,06
        givenBank(
                tx("old", "Compra no débito - Dias E Damasceno Ltda", "-70.00", "DEBIT", "POSTED", "2026-08-02T15:00:00Z"),
                tx("same", "Pix enviado  - Pix Marketplace", "-144.06", "DEBIT", "POSTED", "2026-08-04T15:00:00Z"),
                tx("new-same-day", "Pix enviado  - Padaria Central", "-10.00", "DEBIT", "POSTED", "2026-08-04T20:00:00Z"),
                // 23h30 de 05/08 em Brasília já é 06/08 em UTC: vale a data local.
                tx("late", "Pix recebido - Fulano de Tal", "500.00", "CREDIT", "POSTED", "2026-08-06T02:30:00Z"),
                tx("pending", "Pix enviado  - Ainda processando", "-5.00", "DEBIT", "PENDING", "2026-08-05T12:00:00Z"));

        ImportPreviewResponse preview = statementImportService.previewFromBank(accountId, userId, TODAY);

        assertThat(preview.format()).isEqualTo(StatementFormat.OPEN_FINANCE);
        assertThat(preview.sourceAccountLabel()).isEqualTo("BANCO INTER · 123456-7");
        assertThat(preview.periodStart()).isEqualTo(LocalDate.of(2026, 8, 4));
        assertThat(preview.rows()).extracting(ImportPreviewRow::externalRef)
                .containsExactly("plg:same", "plg:new-same-day", "plg:late");
        assertThat(preview.rows()).extracting(ImportPreviewRow::possibleDuplicate).containsExactly(true, false, false);
        assertThat(preview.duplicateCount()).isZero();

        ImportPreviewRow late = preview.rows().get(2);
        assertThat(late.date()).isEqualTo(LocalDate.of(2026, 8, 5));
        assertThat(late.rawHistory()).isEqualTo("Pix recebido");
        assertThat(late.description()).isEqualTo("Fulano de Tal");
        assertThat(late.type()).isEqualTo(TransactionType.INCOME);
        assertThat(late.paymentMethod()).isEqualTo(PaymentMethodSlug.PIX);
        assertThat(late.amount()).isEqualByComparingTo("500.00");

        // O usuário importa só as duas novas; a possível duplicata fica de fora.
        commitBank(preview.rows().stream().filter(row -> !row.possibleDuplicate()).toList());

        assertThat(statementImportService.previewFromBank(accountId, userId, TODAY).rows())
                .extracting(ImportPreviewRow::externalRef).containsExactly("plg:same");
    }

    @Test
    void futureCardInstallmentsFromAnInvoiceImport_doNotMoveTheCut() {
        commitFile();
        // Compras parceladas da fatura: apontam para a conta que paga e vão até 2027, com chave
        // de import como as do PDF.
        UUID cardId = creditCardService.createCard(new CreateCardRequest("Inter", CardBrand.MASTERCARD, "Inter",
                null, new BigDecimal("10000.00"), 10, 17, null), userId).id();
        installmentService.createInstallmentSeries(new CreateInstallmentRequest(accountId, new BigDecimal("336.81"),
                8, LocalDate.of(2026, 7, 24), "CP PARC SHOPPING INTER", null, null, null,
                PaymentMethodSlug.CREDIT_CARD, cardId), userId);
        jdbcTemplate.update("UPDATE transactions SET external_ref = md5(id::text) WHERE credit_card_id = ?", cardId);
        givenBank(tx("same", "Pix enviado  - Pix Marketplace", "-144.06", "DEBIT", "POSTED", "2026-08-04T15:00:00Z"));

        ImportPreviewResponse preview = statementImportService.previewFromBank(accountId, userId, TODAY);

        assertThat(preview.periodStart()).isEqualTo(LocalDate.of(2026, 8, 4));
        assertThat(preview.rows()).extracting(ImportPreviewRow::externalRef).containsExactly("plg:same");
    }

    @Test
    void fileImportAfterTheBank_flagsWhatTheBankAlreadyBrought() {
        // Sem arquivo importado não há corte: vem tudo o que a Pluggy tem.
        givenBank(tx("pix", "Pix enviado  - Pix Marketplace", "-144.06", "DEBIT", "POSTED", "2026-08-04T15:00:00Z"));
        commitBank(statementImportService.previewFromBank(accountId, userId, TODAY).rows());

        ImportPreviewResponse file = statementImportService.preview(fixture(), StatementFormat.INTER_CSV, accountId, userId);

        assertThat(file.rows()).filteredOn(ImportPreviewRow::possibleDuplicate)
                .extracting(ImportPreviewRow::description).containsExactly("Pix Marketplace");
    }

    @Test
    void accountWithoutLink_isRefused() {
        UUID other = accountService.createAccount(
                new CreateAccountRequest("Outra", AccountType.CHECKING, "BRL", null, 0, null), userId).id();

        assertThatThrownBy(() -> statementImportService.previewFromBank(other, userId, TODAY))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não está vinculada");
    }

    private void givenBank(PluggyTransaction... transactions) {
        when(pluggyClient.listTransactions(externalAccountId)).thenReturn(List.of(transactions));
    }

    private static PluggyTransaction tx(String id, String description, String amount, String type, String status, String date) {
        return new PluggyTransaction(id, description, new BigDecimal(amount), type, status, Instant.parse(date));
    }

    private void commitFile() {
        ImportPreviewResponse preview = statementImportService.preview(fixture(), StatementFormat.INTER_CSV, accountId, userId);
        statementImportService.commit(new ImportCommitRequest(accountId, StatementFormat.INTER_CSV,
                toCommit(preview.rows()), null), userId);
    }

    private void commitBank(List<ImportPreviewRow> rows) {
        statementImportService.commit(new ImportCommitRequest(accountId, StatementFormat.OPEN_FINANCE, toCommit(rows), null), userId);
    }

    private static List<ImportCommitRow> toCommit(List<ImportPreviewRow> rows) {
        return rows.stream()
                .map(row -> new ImportCommitRow(
                        row.lineNumber(), row.externalRef(), row.date(), row.description(), row.description(),
                        row.amount(), row.type(), row.paymentMethod(), row.suggestedCategoryId(),
                        row.outgoing(), row.transferAccountId(), row.invoiceId()))
                .toList();
    }

    private MockMultipartFile fixture() {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/extrato-inter.csv")) {
            return new MockMultipartFile("file", "extrato-inter.csv", "text/csv", in.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
