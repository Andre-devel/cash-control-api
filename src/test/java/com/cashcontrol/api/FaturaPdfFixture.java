package com.cashcontrol.api;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Gera um PDF de fatura sintético, com a mesma estrutura da fatura real do Inter.
 *
 * <p>Gerado em tempo de teste em vez de versionado como binário por dois motivos:
 * o PDF real do usuário tem nome, endereço e número de cartão dele, e um blob no
 * repositório não deixaria ninguém ver o que está sendo testado. Aqui o conteúdo
 * está à vista e é a ponte de verdade com o PDFBox — {@code extractText} roda
 * sobre um PDF de verdade.
 */
final class FaturaPdfFixture {

    /** Vencimento declarado no PDF; a fatura de referência é o mês anterior. */
    static final String DUE_DATE = "07/08/2026";
    static final String REFERENCE_MONTH = "2026-07";

    static final String CARD_A_LAST4 = "1234";
    static final String CARD_B_LAST4 = "5678";

    /** Soma das despesas do cartão A: 55,19 + 110,00. O pagamento não entra. */
    static final String CARD_A_TOTAL = "165.19";
    static final String CARD_B_TOTAL = "336.81";

    private static final List<String> LINES = List.of(
            "TESTE DA SILVA",
            "0000****1234 07/08/2026 R$ 502,00",
            "Despesas da fatura",
            "CARTÃO 0000****1234",
            "Data Movimentação Beneficiário Valor",
            "04 de abr. 2026 LOJA DE TESTE (Parcela 04 de 05) - R$ 55,19",
            "07 de jul. 2026 PAGTO DEBITO AUTOMATICO - + R$ 500,00",
            "15 de jul. 2026 ASSINATURA MENSAL - R$ 110,00",
            "Total CARTÃO 0000****1234 R$ 165,19",
            "CARTÃO 0000****5678",
            "Data Movimentação Beneficiário Valor",
            "24 de jul. 2026 OUTRA LOJA (Parcela 01 de 10) - R$ 336,81",
            "Total CARTÃO 0000****5678 R$ 336,81",
            "Próxima fatura",
            "Data de corte: 30/08/2026",
            "04 de abr. 2026 LOJA DE TESTE (Parcela 05 de 05) - R$ 55,19");

    /**
     * A fatura do mês seguinte do mesmo cartão.
     *
     * <p>Traz a parcela 5 da mesma compra — com a data da compra original, que é como o
     * Inter escreve — e uma compra nova. Serve para exercer o encontro entre a parcela que
     * a importação de julho gerou e a linha que agosto traz.
     */
    private static final List<String> NEXT_MONTH_LINES = List.of(
            "TESTE DA SILVA",
            "0000****1234 07/09/2026 R$ 97,19",
            "Despesas da fatura",
            "CARTÃO 0000****1234",
            "Data Movimentação Beneficiário Valor",
            "04 de abr. 2026 LOJA DE TESTE (Parcela 05 de 05) - R$ 55,19",
            "10 de ago. 2026 MERCADO NOVO - R$ 42,00",
            "Total CARTÃO 0000****1234 R$ 97,19");

    static final String NEXT_REFERENCE_MONTH = "2026-08";

    private FaturaPdfFixture() {}

    static byte[] bytes() {
        return render(LINES);
    }

    static byte[] nextMonthBytes() {
        return render(NEXT_MONTH_LINES);
    }

    private static byte[] render(List<String> lines) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);

            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                content.beginText();
                content.newLineAtOffset(40, 760);
                for (String line : lines) {
                    content.showText(line);
                    content.newLineAtOffset(0, -16);
                }
                content.endText();
            }

            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
