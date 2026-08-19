package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.StatementFormat;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateCategoryRuleRequest;
import com.cashcontrol.api.dto.request.ImportCommitRequest;
import com.cashcontrol.api.dto.request.ImportCommitRow;
import com.cashcontrol.api.dto.response.CategoryResponse;
import com.cashcontrol.api.dto.response.ImportPreviewResponse;
import com.cashcontrol.api.dto.response.ImportPreviewRow;
import com.cashcontrol.api.dto.response.ImportResultResponse;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.CategoryService;
import com.cashcontrol.api.service.StatementImportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Importação de ponta a ponta contra um Postgres real: é aqui que o índice único
 * de {@code external_ref} e o comportamento de reimportação são de fato exercidos.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class StatementImportIntegrationTest {

    @Autowired private StatementImportService statementImportService;
    @Autowired private AccountService accountService;
    @Autowired private CategoryService categoryService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID accountId;

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
                "statement-import-" + UUID.randomUUID() + "@example.com");

        accountId = accountService.createAccount(
                new CreateAccountRequest("Conta Inter", AccountType.CHECKING, "BRL", null, 0, null),
                userId).id();
    }

    @Test
    void previewThenCommit_importsEveryReadableRow() {
        ImportPreviewResponse preview = preview();

        assertThat(preview.totalRows()).isEqualTo(17);
        assertThat(preview.duplicateCount()).isZero();
        assertThat(preview.errors()).hasSize(2);

        ImportResultResponse result = commit(preview.rows());

        assertThat(result.imported()).isEqualTo(17);
        assertThat(result.failed()).isZero();
        assertThat(transactionRepository.findAllByAccount_IdAndUserId(accountId, userId,
                PageRequest.of(0, 50)).getTotalElements()).isEqualTo(17);
    }

    @Test
    void reimportingTheSameStatement_isANoOp() {
        commit(preview().rows());

        // Segunda passada do mesmo arquivo: a prévia já sabe que tudo entrou...
        ImportPreviewResponse second = preview();
        assertThat(second.duplicateCount()).isEqualTo(17);
        assertThat(second.importableCount()).isZero();

        // ...e o commit, mesmo mandando tudo de novo, não grava nada.
        ImportResultResponse result = commit(second.rows());
        assertThat(result.imported()).isZero();
        assertThat(result.skippedDuplicates()).isEqualTo(17);
        assertThat(transactionRepository.findAllByAccount_IdAndUserId(accountId, userId,
                PageRequest.of(0, 50)).getTotalElements()).isEqualTo(17);
    }

    @Test
    void importedTransactions_moveTheAccountBalanceByTheStatementNetAmount() {
        ImportPreviewResponse preview = preview();

        // Soma esperada a partir da própria prévia: receitas e estornos entram, despesas saem.
        BigDecimal expected = preview.rows().stream()
                .map(row -> row.type() == TransactionType.EXPENSE ? row.amount().negate() : row.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        commit(preview.rows());

        assertThat(transactionRepository.sumPaidAmountByAccountIdAndUserId(accountId, userId))
                .isEqualByComparingTo(expected);
    }

    @Test
    void importedTransactions_areSettledOnTheStatementDate() {
        commit(preview().rows());

        transactionRepository.findAllByAccount_IdAndUserId(accountId, userId,
                        PageRequest.of(0, 50))
                .forEach(tx -> {
                    assertThat(tx.getStatus()).isEqualTo(TransactionStatus.PAID);
                    assertThat(tx.getPaymentDate()).isEqualTo(tx.getCompetenceDate());
                    assertThat(tx.getExternalRef()).isNotNull();
                    assertThat(tx.getAmount()).isPositive();
                });
    }

    @Test
    void preview_appliesTheUserCategoryRules() {
        CategoryResponse food = categoryService.listCategories(userId, false, false).stream()
                .filter(category -> category.name().equals("Alimentação"))
                .findFirst()
                .orElseThrow();
        categoryService.createRule(new CreateCategoryRuleRequest("cafe do ponto", food.id(), null, null, 0), userId);

        ImportPreviewResponse preview = preview();

        assertThat(preview.rows())
                .filteredOn(row -> row.description().startsWith("Cafe Do Ponto"))
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.suggestedCategoryId()).isEqualTo(food.id()));

        commit(preview.rows());

        assertThat(transactionRepository.findAllByAccount_IdAndUserId(accountId, userId,
                        PageRequest.of(0, 50)))
                .filteredOn(tx -> tx.getDescription().startsWith("Cafe Do Ponto"))
                .isNotEmpty()
                .allSatisfy(tx -> assertThat(tx.getCategory().getId()).isEqualTo(food.id()));
    }

    @Test
    void twoIdenticalRowsOnTheSameDay_bothSurvive() {
        // Dois cafés de R$ 8,00 em 15/05 são dois fatos; o dedup não pode comê-los.
        commit(preview().rows());

        assertThat(transactionRepository.findAllByAccount_IdAndUserId(accountId, userId,
                        PageRequest.of(0, 50)))
                .filteredOn(tx -> tx.getDescription().startsWith("Cafe Do Ponto"))
                .hasSize(2);
    }

    @Test
    void commit_ofASubsetOnly_importsJustThatSubset() {
        ImportPreviewResponse preview = preview();
        List<ImportPreviewRow> subset = preview.rows().subList(0, 3);

        assertThat(commit(subset).imported()).isEqualTo(3);

        // O restante continua importável na próxima passada.
        assertThat(preview().importableCount()).isEqualTo(14);
    }

    // ── apoio ─────────────────────────────────────────────────────────────────

    private ImportPreviewResponse preview() {
        return statementImportService.preview(fixture(), StatementFormat.INTER_CSV, accountId, userId);
    }

    private ImportResultResponse commit(List<ImportPreviewRow> rows) {
        List<ImportCommitRow> approved = rows.stream()
                .map(row -> new ImportCommitRow(
                        row.lineNumber(), row.externalRef(), row.date(), row.description(), row.description(),
                        row.amount(), row.type(), row.paymentMethod(), row.suggestedCategoryId()))
                .toList();
        return statementImportService.commit(
                new ImportCommitRequest(accountId, StatementFormat.INTER_CSV, approved), userId);
    }

    private MultipartFile fixture() {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/extrato-inter.csv")) {
            return new MockMultipartFile("file", "extrato-inter.csv", "text/csv", in.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
