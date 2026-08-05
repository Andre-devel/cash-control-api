package com.cashcontrol.api;

import com.cashcontrol.api.domain.entity.StatementFormat;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.service.statement.InterCsvStatementParser;
import com.cashcontrol.api.service.statement.ParsedStatement;
import com.cashcontrol.api.service.statement.ParsedStatementRow;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InterCsvStatementParserTest {

    private final InterCsvStatementParser parser = new InterCsvStatementParser();

    private ParsedStatement parseFixture() {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/extrato-inter.csv")) {
            assertThat(in).as("fixture do extrato").isNotNull();
            return parser.parse(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ParsedStatement parse(String content) {
        return parser.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void format_isInterCsv() {
        assertThat(parser.format()).isEqualTo(StatementFormat.INTER_CSV);
    }

    @Test
    void parse_readsPreambleMetadata() {
        ParsedStatement statement = parseFixture();

        assertThat(statement.sourceAccountLabel()).isEqualTo("123456789");
        assertThat(statement.periodStart()).isEqualTo(LocalDate.of(2024, 8, 6));
        assertThat(statement.periodEnd()).isEqualTo(LocalDate.of(2026, 8, 5));
    }

    @Test
    void parse_readsEveryValidRow_andCollectsMalformedOnesAsErrors() {
        ParsedStatement statement = parseFixture();

        assertThat(statement.rows()).hasSize(17);
        assertThat(statement.errors()).hasSize(2);
        assertThat(statement.errors()).extracting(e -> e.message())
                .anySatisfy(m -> assertThat(m).contains("Data inválida"))
                .anySatisfy(m -> assertThat(m).contains("Valor inválido"));
    }

    @Test
    void parse_keepsFileOrderAndLineNumbers() {
        ParsedStatement statement = parseFixture();

        ParsedStatementRow first = statement.rows().get(0);
        assertThat(first.lineNumber()).isEqualTo(7);
        assertThat(first.date()).isEqualTo(LocalDate.of(2026, 8, 4));
        assertThat(first.description()).isEqualTo("Pix Marketplace");
        assertThat(first.signedAmount()).isEqualByComparingTo("-144.06");
    }

    @Test
    void parse_readsBrazilianAmounts_withThousandsSeparatorAndSign() {
        ParsedStatement statement = parseFixture();

        assertThat(statement.rows())
                .filteredOn(r -> r.description().startsWith("Cdb Pernambucan"))
                .singleElement()
                .satisfies(r -> assertThat(r.signedAmount()).isEqualByComparingTo("-9000.00"));

        assertThat(statement.rows())
                .filteredOn(r -> r.description().startsWith("Cdb Di Flut"))
                .singleElement()
                .satisfies(r -> assertThat(r.signedAmount()).isEqualByComparingTo("3978.80"));
    }

    @Test
    void parse_collapsesTheFixedWidthPaddingInTheDescription() {
        // O extrato alinha em colunas: "Dias E Damasceno Ltda  Penapolis     Bra".
        // Esse enchimento não é informação e não pode ir para o banco.
        ParsedStatement statement = parseFixture();

        assertThat(statement.rows()).extracting(ParsedStatementRow::description)
                .contains("Dias E Damasceno Ltda Penapolis Bra")
                .allSatisfy(description -> assertThat(description).doesNotContain("  "));
    }

    @Test
    void parse_keepsRawHistory_includingTrailingSpace() {
        ParsedStatement statement = parseFixture();

        assertThat(statement.rows()).extracting(ParsedStatementRow::rawHistory)
                .contains("Pix enviado", "Compra Inter Shop", "Pix recebido devolvido");
    }

    @Test
    void parse_skipsBomOnFirstLine() {
        ParsedStatement statement = parse("""
                \uFEFF Extrato Conta Corrente
                Conta ;123
                Data Lançamento;Histórico;Descrição;Valor;Saldo
                01/03/2026;Pix recebido;Alguem;10,00;10,00
                """);

        assertThat(statement.sourceAccountLabel()).isEqualTo("123");
        assertThat(statement.rows()).hasSize(1);
    }

    @Test
    void parse_findsHeaderRegardlessOfPreambleLength() {
        ParsedStatement noPreamble = parse("""
                Data Lançamento;Histórico;Descrição;Valor;Saldo
                01/03/2026;Pix recebido;Alguem;10,00;10,00
                """);

        assertThat(noPreamble.rows()).hasSize(1);
        assertThat(noPreamble.sourceAccountLabel()).isNull();
    }

    @Test
    void parse_keepsSemicolonsThatBelongToTheDescription() {
        ParsedStatement statement = parse("""
                Data Lançamento;Histórico;Descrição;Valor;Saldo
                01/03/2026;Pix enviado ;Loja A; Filial B;-10,00;90,00
                """);

        assertThat(statement.rows()).singleElement()
                .satisfies(r -> {
                    assertThat(r.description()).isEqualTo("Loja A; Filial B");
                    assertThat(r.signedAmount()).isEqualByComparingTo("-10.00");
                });
    }

    @Test
    void parse_rejectsZeroAmount() {
        ParsedStatement statement = parse("""
                Data Lançamento;Histórico;Descrição;Valor;Saldo
                01/03/2026;Pix recebido;Alguem;0,00;10,00
                """);

        assertThat(statement.rows()).isEmpty();
        assertThat(statement.errors()).singleElement()
                .satisfies(e -> assertThat(e.message()).contains("zerado"));
    }

    @Test
    void parse_rejectsRowWithMissingColumns() {
        ParsedStatement statement = parse("""
                Data Lançamento;Histórico;Descrição;Valor;Saldo
                01/03/2026;Pix recebido;Alguem
                """);

        assertThat(statement.rows()).isEmpty();
        assertThat(statement.errors()).singleElement()
                .satisfies(e -> assertThat(e.message()).contains("colunas"));
    }

    @Test
    void parse_ignoresBlankLines() {
        ParsedStatement statement = parse("""
                Data Lançamento;Histórico;Descrição;Valor;Saldo

                01/03/2026;Pix recebido;Alguem;10,00;10,00

                """);

        assertThat(statement.rows()).hasSize(1);
        assertThat(statement.errors()).isEmpty();
    }

    @Test
    void parse_withoutHeader_isRejectedAsWrongFormat() {
        assertThatThrownBy(() -> parse("data,descricao,valor\n2026-03-01,Alguem,10.00\n"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não reconhecido");
    }

    @Test
    void parse_malformedPeriod_doesNotBreakTheFile() {
        ParsedStatement statement = parse("""
                Período ;sempre
                Data Lançamento;Histórico;Descrição;Valor;Saldo
                01/03/2026;Pix recebido;Alguem;10,00;10,00
                """);

        assertThat(statement.periodStart()).isNull();
        assertThat(statement.periodEnd()).isNull();
        assertThat(statement.rows()).hasSize(1);
    }

    @Test
    void parse_emptyDescription_fallsBackToHistory() {
        ParsedStatement statement = parse("""
                Data Lançamento;Histórico;Descrição;Valor;Saldo
                01/03/2026;Pix recebido;;10,00;10,00
                """);

        assertThat(statement.rows()).singleElement()
                .satisfies(r -> assertThat(r.description()).isEqualTo("Pix recebido"));
    }

    @Test
    void parse_ignoresBalanceColumn() {
        // O saldo no arquivo real não fecha com a sequência de valores; se fosse lido
        // para algo, este extrato inconsistente falharia. Deve ser simplesmente ignorado.
        ParsedStatement statement = parse("""
                Data Lançamento;Histórico;Descrição;Valor;Saldo
                01/03/2026;Pix recebido;Alguem;10,00;nada disso
                """);

        assertThat(statement.errors()).isEmpty();
        assertThat(statement.rows()).singleElement()
                .satisfies(r -> assertThat(r.signedAmount()).isEqualByComparingTo(new BigDecimal("10.00")));
    }
}
