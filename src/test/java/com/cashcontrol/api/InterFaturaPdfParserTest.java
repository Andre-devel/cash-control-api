package com.cashcontrol.api;

import com.cashcontrol.api.domain.entity.InvoiceImportFormat;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.service.fatura.InterFaturaPdfParser;
import com.cashcontrol.api.service.fatura.ParsedCardSection;
import com.cashcontrol.api.service.fatura.ParsedFatura;
import com.cashcontrol.api.service.fatura.ParsedFaturaRow;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercita {@link InterFaturaPdfParser#parseText(String)} com texto sintético.
 *
 * <p>O texto abaixo não é inventado: é a estrutura que o PDFBox produz para a
 * fatura real do Inter, com os detalhes que o parser precisa aguentar — o
 * cabeçalho repetido em toda página, a seção de um cartão quebrada em duas
 * páginas, a linha de pagamento com traço e sinal, e o bloco "Próxima fatura"
 * que não pode ser importado.
 *
 * <p>Testar assim, e não contra um PDF binário, mantém os casos de borda legíveis
 * e editáveis. A ponte com o PDFBox é coberta em {@link FaturaImportIntegrationTest}.
 */
class InterFaturaPdfParserTest {

    private static final String FATURA = """
            Resumo da fatura
            Limite de crédito total
            Total da sua fatura R$ 8.400,00
            R$ 1.617,29 Data de Vencimento
            Este é o valor que você precisa pagar nesse mês 07/08/2026
            TESTE DA SILVA
            0000****1234 07/08/2026 R$ 1.617,29
            Despesas da fatura
            CARTÃO 0000****1234
            Data Movimentação Beneficiário Valor
            04 de abr. 2026 LOJA DE TESTE (Parcela 04 de 05) - R$ 55,19
            07 de jul. 2026 PAGTO DEBITO AUTOMATICO - + R$ 2.241,47
            15 de jul. 2026 ASSINATURA MENSAL - R$ 110,00
            Total CARTÃO 0000****1234 R$ 165,19
            CARTÃO 0000****5678
            Data Movimentação Beneficiário Valor
            14 de ago. 2025 MERCADO DE TESTE (Parcela 12 de 12) - R$ 84,94
            TESTE DA SILVA
            0000****1234 07/08/2026 R$ 1.617,29
            Despesas da fatura
            CARTÃO 0000****5678
            Data Movimentação Beneficiário Valor
            24 de jul. 2026 OUTRA LOJA (Parcela 01 de 10) - R$ 336,81
            Total CARTÃO 0000****5678 R$ 421,75
            Próxima fatura
            Data de corte: 30/08/2026
            Movimentação Valor
            05 de ago. 2026 LOJA DE TESTE (Parcela 05 de 05) - R$ 55,19
            """;

    private final InterFaturaPdfParser parser = new InterFaturaPdfParser();

    @Test
    void format_isInterFaturaPdf() {
        assertThat(parser.format()).isEqualTo(InvoiceImportFormat.INTER_FATURA_PDF);
    }

    @Test
    void parseText_readsDueDateAndTotalFromThePageHeader() {
        ParsedFatura fatura = parser.parseText(FATURA);

        assertThat(fatura.dueDate()).isEqualTo(LocalDate.of(2026, 8, 7));
        // A primeira página é diagramada em duas colunas e o texto extraído produz
        // "Total da sua fatura R$ 8.400,00", que é o limite do cartão. O total precisa
        // vir do cabeçalho de página, onde rótulo e valor não se desencontram.
        assertThat(fatura.totalAmount()).isEqualByComparingTo("1617.29");
        assertThat(fatura.errors()).isEmpty();
    }

    @Test
    void parseText_splitsTheFileByCardSection() {
        ParsedFatura fatura = parser.parseText(FATURA);

        assertThat(fatura.cardSections()).extracting(ParsedCardSection::cardLast4)
                .containsExactly("1234", "5678");
    }

    @Test
    void parseText_mergesASectionThatBrokeAcrossPages() {
        ParsedFatura fatura = parser.parseText(FATURA);

        // O cartão 5678 tem uma linha antes da quebra de página e outra depois, e o PDF
        // reabre o cabeçalho "CARTÃO ****5678". As duas linhas são do mesmo cartão.
        ParsedCardSection second = fatura.cardSections().get(1);
        assertThat(second.rows()).extracting(ParsedFaturaRow::description)
                .containsExactly("MERCADO DE TESTE (Parcela 12 de 12)", "OUTRA LOJA (Parcela 01 de 10)");
    }

    @Test
    void parseText_keepsCreditsWithAPositiveAmount() {
        ParsedFatura fatura = parser.parseText(FATURA);

        // O pagamento sai do PDF como "PAGTO DEBITO AUTOMATICO - + R$ 2.241,47": o traço
        // é a coluna de beneficiário vazia e não pode virar parte da descrição nem o sinal.
        // Quem descarta a linha é o serviço de importação, não o parser.
        assertThat(fatura.cardSections().getFirst().rows())
                .filteredOn(row -> row.signedAmount().signum() > 0)
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.description()).isEqualTo("PAGTO DEBITO AUTOMATICO");
                    assertThat(row.signedAmount()).isEqualByComparingTo("2241.47");
                });
    }

    @Test
    void parseText_readsDateDescriptionAndSignedAmount() {
        ParsedFaturaRow row = parser.parseText(FATURA).cardSections().getFirst().rows().getFirst();

        assertThat(row.date()).isEqualTo(LocalDate.of(2026, 4, 4));
        assertThat(row.description()).isEqualTo("LOJA DE TESTE (Parcela 04 de 05)");
        assertThat(row.signedAmount()).isEqualByComparingTo("-55.19");
    }

    @Test
    void parseText_readsTheInstallmentSuffix_withoutStrippingItFromTheDescription() {
        ParsedFaturaRow installment = parser.parseText(FATURA).cardSections().getFirst().rows().getFirst();
        ParsedFaturaRow single = parser.parseText(FATURA).cardSections().getFirst().rows().get(2);

        assertThat(installment.installmentNumber()).isEqualTo(4);
        assertThat(installment.totalInstallments()).isEqualTo(5);
        // A descrição mantém o sufixo: é por ele que o usuário reconhece o lançamento.
        assertThat(installment.description()).endsWith("(Parcela 04 de 05)");

        assertThat(single.installmentNumber()).isNull();
        assertThat(single.totalInstallments()).isNull();
    }

    @Test
    void parseText_stopsAtTheNextInvoiceSection() {
        ParsedFatura fatura = parser.parseText(FATURA);

        // "Próxima fatura" repete as parcelas que só vencem no mês seguinte. Importá-las
        // lançaria na fatura atual compras que ainda não foram cobradas.
        assertThat(fatura.cardSections())
                .flatExtracting(ParsedCardSection::rows)
                .extracting(ParsedFaturaRow::description)
                .doesNotContain("LOJA DE TESTE (Parcela 05 de 05)");
    }

    @Test
    void parseText_ignoresEverythingBeforeTheExpensesSection() {
        String withNoiseUpFront = """
                Resumo da fatura
                0000****1234 07/08/2026 R$ 100,00
                Parcelamento Total
                04 de abr. 2026 SIMULACAO QUE NAO E LANCAMENTO - R$ 999,00
                Despesas da fatura
                CARTÃO 0000****1234
                04 de abr. 2026 LOJA DE TESTE - R$ 55,19
                Total CARTÃO 0000****1234 R$ 55,19
                """;

        assertThat(parser.parseText(withNoiseUpFront).cardSections())
                .flatExtracting(ParsedCardSection::rows)
                .extracting(ParsedFaturaRow::description)
                .containsExactly("LOJA DE TESTE");
    }

    @Test
    void parseText_reportsAnUnreadableRowWithoutLosingTheRest() {
        String withBadRow = """
                0000****1234 07/08/2026 R$ 100,00
                Despesas da fatura
                CARTÃO 0000****1234
                04 de xxx. 2026 MES INEXISTENTE - R$ 10,00
                31 de fev. 2026 DIA INEXISTENTE - R$ 20,00
                04 de abr. 2026 LOJA DE TESTE - R$ 55,19
                Total CARTÃO 0000****1234 R$ 55,19
                """;

        ParsedFatura fatura = parser.parseText(withBadRow);

        assertThat(fatura.cardSections().getFirst().rows()).hasSize(1);
        assertThat(fatura.errors()).extracting(error -> error.message())
                .anySatisfy(message -> assertThat(message).contains("Mês desconhecido"))
                .anySatisfy(message -> assertThat(message).contains("Data inválida"));
    }

    @Test
    void parseText_rejectsAFileWithoutTheExpensesSection() {
        assertThatThrownBy(() -> parser.parseText("Extrato Conta Corrente\nConta ;123456789\n"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não reconhecido como fatura");
    }

    @Test
    void parseText_rejectsAFaturaWithoutADueDate() {
        String noDueDate = """
                Despesas da fatura
                CARTÃO 0000****1234
                04 de abr. 2026 LOJA DE TESTE - R$ 55,19
                Total CARTÃO 0000****1234 R$ 55,19
                """;

        assertThatThrownBy(() -> parser.parseText(noDueDate))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("data de vencimento");
    }

    /**
     * A única ponte com o PDFBox: o restante da classe trabalha sobre texto. Se a
     * extração parar de devolver uma linha visual por linha de texto, é aqui que quebra.
     */
    @Test
    void parse_readsARealPdfEndToEnd() {
        ParsedFatura fatura = parser.parse(new ByteArrayInputStream(FaturaPdfFixture.bytes()));

        assertThat(fatura.dueDate()).isEqualTo(LocalDate.of(2026, 8, 7));
        assertThat(fatura.cardSections()).extracting(ParsedCardSection::cardLast4)
                .containsExactly(FaturaPdfFixture.CARD_A_LAST4, FaturaPdfFixture.CARD_B_LAST4);
        assertThat(fatura.cardSections().getFirst().rows()).hasSize(3);
        assertThat(fatura.errors()).isEmpty();
    }

    @Test
    void extractText_rejectsAFileThatIsNotAPdf() {
        try (InputStream in = InputStream.nullInputStream()) {
            assertThatThrownBy(() -> parser.extractText(in))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("ler o arquivo como PDF");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void parseText_readsAmountsWithThousandsSeparator() {
        String bigAmount = """
                0000****1234 07/08/2026 R$ 100,00
                Despesas da fatura
                CARTÃO 0000****1234
                04 de abr. 2026 COMPRA GRANDE - R$ 12.345,67
                """;

        assertThat(parser.parseText(bigAmount).cardSections().getFirst().rows().getFirst().signedAmount())
                .isEqualByComparingTo(new BigDecimal("-12345.67"));
    }
}
