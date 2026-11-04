package com.cashcontrol.api.service;

/**
 * @author Andre
 * 4 de Novembro. de 2026
 */

import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.response.RecurrenceSuggestionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RecurrenceDetectorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);
    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    @Test
    void normalize_lowercasesAndDropsDigitsDatesInstallmentsAndAsterisks() {
        assertThat(RecurrenceDetector.normalize("HOSTINGER*  BR 12/10 Parcela 3/10"))
                .isEqualTo("hostinger br parcela");
        assertThat(RecurrenceDetector.normalize("NETFLIX.COM 2026-10-12")).isEqualTo("netflix");
        assertThat(RecurrenceDetector.normalize("Pag*Spotify   1234")).isEqualTo("pag spotify");
    }

    @Test
    void normalize_keepsOnlyFirstThreeSignificantWords() {
        assertThat(RecurrenceDetector.normalize("Conta de Luz da Casa Nova")).isEqualTo("conta luz casa");
    }

    @Test
    void normalize_handlesNullAndOnlyDigits() {
        assertThat(RecurrenceDetector.normalize(null)).isEmpty();
        assertThat(RecurrenceDetector.normalize("123 / 456")).isEmpty();
    }

    @Test
    void detect_groupsWhenPresentInThreeDistinctMonths() {
        List<Transaction> txs = List.of(
                expense("HOSTINGER* 1/12", "39.90", LocalDate.of(2026, 8, 12)),
                expense("HOSTINGER* 2/12", "39.90", LocalDate.of(2026, 9, 12)),
                expense("Hostinger 3/12", "41.00", LocalDate.of(2026, 10, 13)));

        List<RecurrenceSuggestionResponse> result = RecurrenceDetector.detect(txs, List.of(), TODAY);

        assertThat(result).hasSize(1);
        RecurrenceSuggestionResponse s = result.getFirst();
        assertThat(s.pattern()).isEqualTo("hostinger");
        assertThat(s.description()).isEqualTo("Hostinger 3/12");
        assertThat(s.amount()).isEqualByComparingTo("39.90");
        assertThat(s.months()).isEqualTo(3);
        assertThat(s.dayOfMonth()).isEqualTo(13);
        assertThat(s.lastDate()).isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(s.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(s.creditCardId()).isNull();
    }

    @Test
    void detect_doesNotGroupTwoMonths() {
        List<Transaction> txs = List.of(
                expense("Spotify", "21.90", LocalDate.of(2026, 9, 5)),
                expense("Spotify", "21.90", LocalDate.of(2026, 9, 20)),
                expense("Spotify", "21.90", LocalDate.of(2026, 10, 5)));

        assertThat(RecurrenceDetector.detect(txs, List.of(), TODAY)).isEmpty();
    }

    @Test
    void detect_doesNotGroupWhenAmountOutsideFivePercent() {
        List<Transaction> txs = List.of(
                expense("Academia", "100.00", LocalDate.of(2026, 8, 1)),
                expense("Academia", "100.00", LocalDate.of(2026, 9, 1)),
                expense("Academia", "106.00", LocalDate.of(2026, 10, 1)));

        assertThat(RecurrenceDetector.detect(txs, List.of(), TODAY)).isEmpty();
    }

    @Test
    void detect_suppressedByActiveRecurrenceWithSameNormalizedDescription() {
        List<Transaction> txs = List.of(
                expense("NETFLIX 08", "55.90", LocalDate.of(2026, 8, 3)),
                expense("NETFLIX 09", "55.90", LocalDate.of(2026, 9, 3)),
                expense("NETFLIX 10", "55.90", LocalDate.of(2026, 10, 3)));

        assertThat(RecurrenceDetector.detect(txs, List.of("Netflix"), TODAY)).isEmpty();
        assertThat(RecurrenceDetector.detect(txs, List.of("Aluguel"), TODAY)).hasSize(1);
    }

    @Test
    void detect_ignoresTransfersInvoicePaymentsReimbursementsAndOldOrUnpaidEntries() {
        List<Transaction> txs = new ArrayList<>();
        for (int month = 8; month <= 10; month++) {
            LocalDate date = LocalDate.of(2026, month, 10);
            Transaction transfer = expense("Envio poupanca", "200.00", date);
            transfer.setTransferGroupId(UUID.randomUUID());
            txs.add(transfer);

            Transaction reimbursable = expense("Almoco cliente", "80.00", date);
            reimbursable.setReimbursable(true);
            txs.add(reimbursable);

            Transaction pending = expense("Seguro carro", "150.00", date);
            pending.setStatus(TransactionStatus.PENDING);
            txs.add(pending);

            Transaction invoicePayment = expense("Pagamento fatura", "900.00", date);
            invoicePayment.setType(TransactionType.INVOICE_PAYMENT);
            txs.add(invoicePayment);

            Transaction income = expense("Salario", "5000.00", date);
            income.setType(TransactionType.INCOME);
            txs.add(income);
        }
        txs.add(expense("Clube antigo", "30.00", LocalDate.of(2026, 1, 10)));
        txs.add(expense("Clube antigo", "30.00", LocalDate.of(2026, 2, 10)));
        txs.add(expense("Clube antigo", "30.00", LocalDate.of(2026, 3, 10)));

        assertThat(RecurrenceDetector.detect(txs, List.of(), TODAY)).isEmpty();
    }

    private static Transaction expense(String description, String amount, LocalDate date) {
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", ACCOUNT_ID);
        Transaction t = new Transaction();
        t.setUserId(UUID.randomUUID());
        t.setAccount(account);
        t.setType(TransactionType.EXPENSE);
        t.setStatus(TransactionStatus.PAID);
        t.setAmount(new BigDecimal(amount));
        t.setDescription(description);
        t.setCompetenceDate(date);
        return t;
    }
}
