package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.FixedIncomeIndexer;
import com.cashcontrol.api.domain.entity.FixedIncomeKind;
import com.cashcontrol.api.domain.entity.FixedIncomeStatus;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateTransactionRequest;
import com.cashcontrol.api.dto.request.FixedIncomeHoldingRequest;
import com.cashcontrol.api.dto.request.TransferRequest;
import com.cashcontrol.api.dto.response.FixedIncomeHoldingResponse;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.TransactionService;
import com.cashcontrol.api.service.investment.BankInvestmentSync;
import com.cashcontrol.api.service.investment.InvestmentService;
import com.cashcontrol.api.service.marketdata.MarketDataService;
import com.cashcontrol.api.service.openfinance.PluggyClient;
import com.cashcontrol.api.service.openfinance.PluggyClient.PluggyInvestment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class BankInvestmentSyncIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(MarketDataService.MARKET_ZONE);
    private static final String ITEM = "item-invest";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AccountService accountService;
    @Autowired private TransactionService transactionService;
    @Autowired private InvestmentService investmentService;
    @Autowired private BankInvestmentSync sync;
    @MockitoBean private PluggyClient pluggyClient;

    private UUID userId;
    private UUID checkingId;
    private UUID investmentId;

    @BeforeEach
    void setUp() {
        userId = createUser();
        checkingId = createAccount("Conta corrente", AccountType.CHECKING);
        investmentId = createAccount("Investimentos", AccountType.INVESTMENT);
        transactionService.createTransaction(new CreateTransactionRequest(checkingId, TransactionType.INCOME,
                new BigDecimal("10000"), "Salário", TODAY.minusDays(100), TODAY.minusDays(100), null, null, null,
                null, null, TransactionStatus.PAID, null, null), userId);
        jdbcTemplate.update("DELETE FROM market_index_rates");
        List<Object[]> rows = new ArrayList<>();
        for (LocalDate day = TODAY.minusDays(100); day.isBefore(TODAY); day = day.plusDays(1)) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                rows.add(new Object[]{day});
            }
        }
        jdbcTemplate.batchUpdate("INSERT INTO market_index_rates (market_index, rate_date, value) VALUES ('CDI', ?, 0.05)", rows);
    }

    @Test
    void firstSync_replacesManualHoldings_linksTheTransfer_andAdjustsTheRest() {
        FixedIncomeHoldingResponse manual = investmentService.createHolding(new FixedIncomeHoldingRequest(investmentId,
                FixedIncomeKind.CDB, FixedIncomeIndexer.CDI_PERCENT, new BigDecimal("100"), "CDB à mão", null, null, null,
                TODAY.minusDays(60), null, new BigDecimal("500.00"), null, null, null, null, null), userId, TODAY);
        accountService.createTransfer(new TransferRequest(checkingId, investmentId, new BigDecimal("1000.00"),
                "Aplicação CDB", TODAY.minusDays(30)), userId);
        when(pluggyClient.listInvestments(ITEM)).thenReturn(List.of(
                cdb("inv-1", "1000.00", "1010.00", TODAY.minusDays(29), "ACTIVE", "102"),
                cdb("inv-2", "300.00", "303.00", TODAY.minusDays(10), "ACTIVE", "100"),
                cdb("inv-old", "0", "0", TODAY.minusDays(80), "TOTAL_WITHDRAWAL", "100")));

        sync.sync(userId, ITEM);

        assertThat(jdbcTemplate.queryForObject("SELECT deleted_at IS NOT NULL FROM fixed_income_holdings WHERE id = ?",
                Boolean.class, manual.id())).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fixed_income_holdings WHERE user_id = ? "
                + "AND external_id IS NOT NULL", Integer.class, userId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT funding_transfer_group_id IS NOT NULL FROM fixed_income_holdings "
                + "WHERE external_id = 'inv-1' AND user_id = ?", Boolean.class, userId)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT manual_value FROM fixed_income_holdings WHERE external_id = 'inv-1' "
                + "AND user_id = ?", BigDecimal.class, userId)).isEqualByComparingTo("1010.00");
        assertThat(jdbcTemplate.queryForObject("SELECT rate FROM fixed_income_holdings WHERE external_id = 'inv-1' "
                + "AND user_id = ?", BigDecimal.class, userId)).isEqualByComparingTo("102");
        // A conta passa a ser o principal do banco: 1000 vieram da transferência, 300 do ajuste.
        assertThat(accountService.computeBalance(investmentId, userId)).isEqualByComparingTo("1300.00");
        assertThat(adjustments()).containsExactly(new BigDecimal("300.00"));
    }

    @Test
    void laterSync_redeemsWhatTheBankWithdrew_andRewritesTheSameAdjustment() {
        when(pluggyClient.listInvestments(ITEM)).thenReturn(List.of(
                cdb("inv-1", "1000.00", "1010.00", TODAY.minusDays(29), "ACTIVE", "100"),
                cdb("inv-2", "300.00", "303.00", TODAY.minusDays(10), "ACTIVE", "100")));
        sync.sync(userId, ITEM);
        assertThat(adjustments()).containsExactly(new BigDecimal("1300.00"));

        // A transferência da aplicação chega depois; inv-2 é resgatado no banco.
        accountService.createTransfer(new TransferRequest(checkingId, investmentId, new BigDecimal("1000.00"),
                "Aplicação CDB", TODAY.minusDays(29)), userId);
        when(pluggyClient.listInvestments(ITEM)).thenReturn(List.of(
                cdb("inv-1", "1000.00", "1012.00", TODAY.minusDays(29), "ACTIVE", "100"),
                cdb("inv-2", "0", "0", TODAY.minusDays(10), "TOTAL_WITHDRAWAL", "100")));
        sync.sync(userId, ITEM);

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM fixed_income_holdings WHERE external_id = 'inv-2' "
                + "AND user_id = ?", String.class, userId)).isEqualTo(FixedIncomeStatus.REDEEMED.name());
        assertThat(accountService.computeBalance(investmentId, userId)).isEqualByComparingTo("1000.00");
        // O rendimento do resgate entrou como receita e o líquido ficou na conta até a transferência
        // de volta chegar; o ajuste, sempre o mesmo lançamento, desconta isso.
        BigDecimal yield = jdbcTemplate.queryForObject("SELECT amount FROM transactions WHERE account_id = ? "
                + "AND type = 'INCOME'", BigDecimal.class, investmentId);
        assertThat(yield).isPositive();
        assertThat(adjustments()).containsExactly(yield.negate().setScale(2));

        // O líquido volta para a corrente: o ajuste some.
        accountService.createTransfer(new TransferRequest(investmentId, checkingId, new BigDecimal("300.00").add(yield),
                "Resgate CDB", TODAY), userId);
        when(pluggyClient.listInvestments(ITEM)).thenReturn(List.of(
                cdb("inv-1", "1000.00", "1012.00", TODAY.minusDays(29), "ACTIVE", "100")));
        sync.sync(userId, ITEM);

        assertThat(accountService.computeBalance(investmentId, userId)).isEqualByComparingTo("1000.00");
        // inv-2 veio do ajuste (sem transferência de entrada); com a saída dele, o ajuste volta a cobri-lo.
        assertThat(adjustments()).containsExactly(new BigDecimal("300.00"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions WHERE account_id = ? "
                + "AND type = 'MANUAL_ADJUSTMENT'", Integer.class, investmentId)).isEqualTo(1);
    }

    @Test
    void manualHoldings_stayWhenTheBankHasNothingActiveToReplaceThem() {
        FixedIncomeHoldingResponse manual = investmentService.createHolding(new FixedIncomeHoldingRequest(investmentId,
                FixedIncomeKind.CDB, FixedIncomeIndexer.CDI_PERCENT, new BigDecimal("100"), "CDB à mão", null, null, null,
                TODAY.minusDays(60), null, new BigDecimal("500.00"), null, null, null, null, null), userId, TODAY);
        when(pluggyClient.listInvestments(ITEM)).thenReturn(List.of(
                cdb("inv-old", "0", "0", TODAY.minusDays(80), "TOTAL_WITHDRAWAL", "100")));

        sync.sync(userId, ITEM);
        sync.sync(userId, ITEM);

        assertThat(jdbcTemplate.queryForObject("SELECT deleted_at IS NULL FROM fixed_income_holdings WHERE id = ?",
                Boolean.class, manual.id())).isTrue();
    }

    @Test
    void transferImportedAfterTheHolding_isLinkedOnTheNextSync() {
        when(pluggyClient.listInvestments(ITEM)).thenReturn(List.of(
                cdb("inv-1", "1000.00", "1010.00", TODAY.minusDays(29), "ACTIVE", "100")));
        sync.sync(userId, ITEM);
        assertThat(funding("inv-1")).isNull();

        accountService.createTransfer(new TransferRequest(checkingId, investmentId, new BigDecimal("1000.00"),
                "Aplicação CDB", TODAY.minusDays(28)), userId);
        sync.sync(userId, ITEM);

        assertThat(funding("inv-1")).isNotNull();
        assertThat(investmentService.listPendingTransfers(userId, TODAY)).isEmpty();
        assertThat(adjustments()).isEmpty();
    }

    @Test
    void oneInvestmentTheModuleRefuses_doesNotStopTheOthers() {
        when(pluggyClient.listInvestments(ITEM)).thenReturn(List.of(
                // Taxa fora da coluna (9,4): a gravação falha só para este.
                cdb("inv-bad", "100.00", "101.00", TODAY.minusDays(20), "ACTIVE", "999999999"),
                cdb("inv-1", "1000.00", "1010.00", TODAY.minusDays(29), "ACTIVE", "100")));

        sync.sync(userId, ITEM);

        assertThat(jdbcTemplate.queryForList("SELECT external_id FROM fixed_income_holdings WHERE user_id = ? "
                + "AND deleted_at IS NULL", String.class, userId)).containsExactly("inv-1");
        assertThat(accountService.computeBalance(investmentId, userId)).isEqualByComparingTo("1000.00");
    }

    @Test
    void holdingLeftInAnArchivedAccount_isStillFoundByItsBankId() {
        when(pluggyClient.listInvestments(ITEM)).thenReturn(List.of(
                cdb("inv-1", "1000.00", "1010.00", TODAY.minusDays(29), "ACTIVE", "100")));
        sync.sync(userId, ITEM);
        jdbcTemplate.update("UPDATE accounts SET archived_at = NOW() WHERE id = ?", investmentId);
        createAccount("Investimentos novos", AccountType.INVESTMENT);
        when(pluggyClient.listInvestments(ITEM)).thenReturn(List.of(
                cdb("inv-1", "1000.00", "1020.00", TODAY.minusDays(29), "ACTIVE", "100")));

        sync.sync(userId, ITEM);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fixed_income_holdings WHERE user_id = ? "
                + "AND external_id = 'inv-1'", Integer.class, userId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT manual_value FROM fixed_income_holdings WHERE external_id = 'inv-1' "
                + "AND user_id = ?", BigDecimal.class, userId)).isEqualByComparingTo("1020.00");
    }

    private UUID funding(String externalId) {
        return jdbcTemplate.queryForObject("SELECT funding_transfer_group_id FROM fixed_income_holdings "
                + "WHERE external_id = ? AND user_id = ?", UUID.class, externalId, userId);
    }

    @Test
    void withTwoInvestmentAccounts_nothingIsTouched() {
        createAccount("Corretora", AccountType.INVESTMENT);
        when(pluggyClient.listInvestments(ITEM)).thenReturn(List.of(
                cdb("inv-1", "1000.00", "1010.00", TODAY.minusDays(29), "ACTIVE", "100")));

        sync.sync(userId, ITEM);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fixed_income_holdings WHERE user_id = ?",
                Integer.class, userId)).isZero();
    }

    private List<BigDecimal> adjustments() {
        return jdbcTemplate.queryForList("SELECT amount FROM transactions WHERE account_id = ? AND type = 'MANUAL_ADJUSTMENT' "
                + "AND amount <> 0", BigDecimal.class, investmentId).stream().map(v -> v.setScale(2)).toList();
    }

    private static PluggyInvestment cdb(String id, String original, String amount, LocalDate purchase, String status, String rate) {
        Instant purchaseAt = purchase.atTime(3, 0).toInstant(ZoneOffset.UTC);
        Instant valuedAt = TODAY.minusDays(1).atTime(18, 0).toInstant(ZoneOffset.UTC);
        return new PluggyInvestment(id, "FIXED_INCOME", "CDB", "CDB - BANCO INTER S.A", "BANCO INTER S.A",
                new BigDecimal(original), new BigDecimal(amount), new BigDecimal(amount), valuedAt, purchaseAt, purchaseAt,
                TODAY.plusYears(2).atTime(3, 0).toInstant(ZoneOffset.UTC), new BigDecimal(rate), "CDI", BigDecimal.ZERO,
                status);
    }

    private UUID createAccount(String name, AccountType type) {
        return accountService.createAccount(new CreateAccountRequest(name, type, "BRL", null, 0, null), userId).id();
    }

    private UUID createUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) "
                        + "VALUES (?, (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), "
                        + "(SELECT id FROM auth_origins WHERE slug = 'LOCAL'), NOW() - INTERVAL '1 minute') RETURNING id",
                UUID.class, "bank-invest-" + UUID.randomUUID() + "@example.com");
    }
}
