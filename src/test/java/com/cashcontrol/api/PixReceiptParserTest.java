package com.cashcontrol.api;

import com.cashcontrol.api.service.receipt.ParsedReceipt;
import com.cashcontrol.api.service.receipt.PixReceiptParser;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testa {@link PixReceiptParser} contra texto sintético, higienizado à mão para reproduzir
 * a forma real de cada banco sem versionar um comprovante de verdade — mesma razão de
 * {@code FaturaPdfFixture} gerar o PDF em tempo de teste em vez de guardar um binário: um
 * comprovante real tem nome e documento de alguém.
 */
class PixReceiptParserTest {

    private final PixReceiptParser parser = new PixReceiptParser();

    @Test
    void leComprovanteEstiloNubank() {
        String text = """
                Comprovante de transferência

                Valor
                R$ 150,00

                Data
                15/08/2026

                Para
                Padaria São João
                123.456.789-00

                Instituição: Banco Inter

                ID da transação
                E12345678202608151030abcdef12345
                """;

        ParsedReceipt receipt = parser.parseText(text);

        assertThat(receipt.amount()).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(receipt.date()).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(receipt.recipientName()).isEqualTo("Padaria São João");
        assertThat(receipt.recipientDocument()).isEqualTo("123.456.789-00");
        assertThat(receipt.endToEndId()).isEqualTo("E12345678202608151030abcdef12345");
        assertThat(receipt.unreadFields()).isEmpty();
    }

    @Test
    void leValorEDataSemRotuloExplicito() {
        String text = """
                Nu Pagamentos S.A.
                Transferência realizada com sucesso

                R$ 42,50

                14/08/2026 às 09:15

                Recebedor: Mercado da Esquina
                CNPJ: 12.345.678/0001-90
                """;

        ParsedReceipt receipt = parser.parseText(text);

        assertThat(receipt.amount()).isEqualByComparingTo(new BigDecimal("42.50"));
        assertThat(receipt.date()).isEqualTo(LocalDate.of(2026, 8, 14));
        assertThat(receipt.recipientName()).isEqualTo("Mercado da Esquina");
        assertThat(receipt.recipientDocument()).isEqualTo("12.345.678/0001-90");
    }

    @Test
    void deduzDestinatarioPelaLinhaAcimaDoDocumentoQuandoNaoHaRotulo() {
        String text = """
                PicPay
                Você pagou

                Loja Exemplo Ltda
                ***.456.789-**

                Valor: R$ 89,90
                Data: 10/08/2026
                """;

        ParsedReceipt receipt = parser.parseText(text);

        assertThat(receipt.recipientName()).isEqualTo("Loja Exemplo Ltda");
    }

    @Test
    void marcaCamposNaoLidosQuandoComprovanteForaDoPadrao() {
        ParsedReceipt receipt = parser.parseText("Texto qualquer sem nenhuma âncora conhecida.");

        assertThat(receipt.amount()).isNull();
        assertThat(receipt.date()).isNull();
        assertThat(receipt.recipientName()).isNull();
        assertThat(receipt.unreadFields()).containsExactlyInAnyOrder("valor", "data", "destinatário");
        assertThat(receipt.isEmpty()).isTrue();
    }

    @Test
    void textoVazioNaoLevantaExcecao() {
        assertThat(parser.parseText("")).isEqualTo(ParsedReceipt.EMPTY);
        assertThat(parser.parseText(null)).isEqualTo(ParsedReceipt.EMPTY);
    }

    @Test
    void valorZeradoNaoEhAceito() {
        ParsedReceipt receipt = parser.parseText("Valor: R$ 0,00\nData: 10/08/2026");

        assertThat(receipt.amount()).isNull();
    }
}
