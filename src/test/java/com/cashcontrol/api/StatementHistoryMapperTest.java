package com.cashcontrol.api;

import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.service.statement.StatementHistoryMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class StatementHistoryMapperTest {

    private final StatementHistoryMapper mapper = new StatementHistoryMapper();

    /** Os 14 históricos que aparecem no extrato real de dois anos. */
    @ParameterizedTest
    @CsvSource({
            "'Pix enviado ',              -144.06, EXPENSE, PIX",
            "'Pix recebido',               157.00, INCOME,  PIX",
            "'Pix enviado devolvido',      139.90, REFUND,  PIX",
            "'Pix recebido devolvido ',      4.05, EXPENSE, PIX",
            "'Compra no débito',           -70.00, EXPENSE, DEBIT_CARD",
            "'Compra Inter Shop ',      -3762.60, EXPENSE, OTHER",
            "'Pagamento efetuado',        -253.44, EXPENSE, BOLETO",
            "'Cashback',                    69.36, REFUND,  OTHER",
            "'Aplicação',                 -413.00, EXPENSE, BANK_TRANSFER",
            "'Débito Renda Fixa',        -9000.00, EXPENSE, BANK_TRANSFER",
            "'Resgate',                    155.00, INCOME,  BANK_TRANSFER",
            "'Crédito Renda Fixa',        3978.80, INCOME,  BANK_TRANSFER",
            "'Crédito liberado',           254.12, INCOME,  BANK_TRANSFER",
            "'Transferência recebida',     200.00, INCOME,  BANK_TRANSFER"
    })
    void map_knownHistories(String rawHistory, BigDecimal amount,
                            TransactionType expectedType, PaymentMethodSlug expectedMethod) {
        StatementHistoryMapper.Mapping mapping = mapper.map(rawHistory, amount);

        assertThat(mapping.type()).isEqualTo(expectedType);
        assertThat(mapping.paymentMethod()).isEqualTo(expectedMethod);
        assertThat(mapping.unknownHistory()).isFalse();
    }

    @Test
    void map_isInsensitiveToCaseSpacingAndAccent() {
        // O mesmo extrato chega ora em UTF-8, ora em ISO-8859-1 mal decodificado:
        // "debito" sem acento não pode virar linha desconhecida.
        assertThat(mapper.map("  COMPRA NO DEBITO  ", new BigDecimal("-10.00")))
                .isEqualTo(mapper.map("Compra no débito", new BigDecimal("-10.00")));
    }

    @Test
    void map_unknownHistory_negativeAmount_becomesExpense() {
        StatementHistoryMapper.Mapping mapping =
                mapper.map("Estorno de tarifa avulsa", new BigDecimal("-12.34"));

        assertThat(mapping.type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(mapping.paymentMethod()).isEqualTo(PaymentMethodSlug.OTHER);
        assertThat(mapping.unknownHistory()).isTrue();
    }

    @Test
    void map_unknownHistory_positiveAmount_becomesIncome() {
        StatementHistoryMapper.Mapping mapping =
                mapper.map("Histórico que o banco inventou ontem", new BigDecimal("12.34"));

        assertThat(mapping.type()).isEqualTo(TransactionType.INCOME);
        assertThat(mapping.unknownHistory()).isTrue();
    }

    @Test
    void map_nullHistory_doesNotBlowUp() {
        assertThat(mapper.map(null, new BigDecimal("-1.00")).unknownHistory()).isTrue();
    }

    @Test
    void map_investmentAndInvoiceRows_stayAsPlainExpenseOrIncome() {
        // Decisão de produto: o extrato é a fonte do saldo da conta corrente, então
        // aplicação, resgate e pagamento de fatura entram como movimento comum para
        // o saldo importado fechar com o do banco.
        assertThat(mapper.map("Aplicação", new BigDecimal("-100.00")).type())
                .isEqualTo(TransactionType.EXPENSE);
        assertThat(mapper.map("Resgate", new BigDecimal("100.00")).type())
                .isEqualTo(TransactionType.INCOME);
        assertThat(mapper.map("Pagamento efetuado", new BigDecimal("-2241.47")).type())
                .isEqualTo(TransactionType.EXPENSE);
    }
}
