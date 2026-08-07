package com.cashcontrol.api.service.fatura;

import com.cashcontrol.api.domain.entity.InvoiceImportFormat;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.dto.response.ImportRowError;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fatura de cartão de crédito do Banco Inter em PDF.
 *
 * <p>O PDF, depois de extraído para texto, tem esta forma na parte que interessa:
 *
 * <pre>
 * Despesas da fatura
 * CARTÃO 2306****7866
 * Data Movimentação Beneficiário Valor
 * 04 de abr. 2026 SHOPEE *LarkSpComercio (Parcela 04 de 05) - R$ 55,19
 * 07 de jul. 2026 PAGTO DEBITO AUTOMATICO + R$ 2.241,47
 * Total CARTÃO 2306****7866 R$ 662,70
 * CARTÃO 2306****4776
 * ...
 * </pre>
 *
 * <p>Três detalhes do arquivo real moldam o algoritmo:
 *
 * <ul>
 *   <li>Um PDF cobre <strong>vários cartões</strong> (titular e adicionais), cada um
 *       na sua seção.</li>
 *   <li>Quando a tabela de um cartão quebra a página, a seção é <strong>reaberta</strong>
 *       com o mesmo cabeçalho na página seguinte. Por isso as seções são agrupadas por
 *       {@code cardLast4}, não por ocorrência de cabeçalho.</li>
 *   <li>O bloco "Próxima fatura" repete as parcelas que ainda vão vencer. Ele é
 *       cortado fora: importá-lo lançaria na fatura atual compras do mês seguinte.</li>
 * </ul>
 *
 * <p>A leitura é dividida em {@link #extractText(InputStream)} (I/O, PDFBox) e
 * {@link #parseText(String)} (regex puro) para que a lógica de parsing possa ser
 * testada com texto sintético, sem depender de um PDF binário.
 */
@Component
public class InterFaturaPdfParser implements FaturaParser {

    /** Onde começam os lançamentos. Tudo antes é resumo, boleto e simulação de parcelamento. */
    private static final String EXPENSES_HEADER = "despesas da fatura";

    /** Onde param: daqui para baixo são as parcelas que só vencem no mês que vem. */
    private static final String NEXT_INVOICE_HEADER = "próxima fatura";

    private static final String SECTION_TOTAL_PREFIX = "total cart";

    private static final Pattern SECTION_HEADER =
            Pattern.compile("^CART[ÃA]O\\s+\\d*\\*+(\\d{4})\\b", Pattern.CASE_INSENSITIVE);

    /**
     * {@code 04 de abr. 2026 SHOPEE *LarkSpComercio (Parcela 04 de 05) - R$ 55,19}
     *
     * <p>O {@code -} opcional antes do sinal é o caso da linha de pagamento, que sai do
     * PDF como {@code PAGTO DEBITO AUTOMATICO - + R$ 2.241,47}: a coluna de beneficiário
     * vazia vira um traço e cola no valor. Sem tolerá-lo, o traço entraria na descrição
     * e o {@code +} nunca seria lido como sinal.
     */
    private static final Pattern ROW = Pattern.compile(
            "^(\\d{1,2})\\s+de\\s+([\\p{L}]{3,12})\\.?\\s+(\\d{4})\\s+(.+?)\\s+(?:-\\s+)?([+-])\\s*R\\$\\s*([\\d.,]+)$");

    private static final Pattern INSTALLMENT =
            Pattern.compile("\\(Parcela\\s+(\\d+)\\s+de\\s+(\\d+)\\)", Pattern.CASE_INSENSITIVE);

    /**
     * Cabeçalho repetido no topo de toda página, com cartão, vencimento e total:
     * {@code 2306****7866 07/08/2026 R$ 1.617,29}.
     *
     * <p>É daqui que saem vencimento e total, e não dos rótulos "Data de Vencimento" /
     * "Total da sua fatura": a primeira página é diagramada em duas colunas e a extração
     * de texto intercala rótulo de uma coluna com valor da outra
     * ({@code Total da sua fatura R$ 8.400,00}, que é o limite do cartão). Esta linha é
     * uma célula só, então rótulo e valor não têm como se desencontrar.
     */
    private static final Pattern INVOICE_HEADER = Pattern.compile(
            "\\d*\\*{2,}\\d{4}\\s+(\\d{2}/\\d{2}/\\d{4})\\s+R\\$\\s*([\\d.,]+)");

    /** Reserva para o vencimento, caso o cabeçalho de página mude entre versões do PDF. */
    private static final Pattern DUE_DATE_LABEL =
            Pattern.compile("Vencimento\\s+(\\d{2}/\\d{2}/\\d{4})");

    /** Reserva para o total: rótulo e valor na mesma célula, no descritivo detalhado. */
    private static final Pattern TOTAL_AMOUNT_LABEL =
            Pattern.compile("Fatura atual\\s+R\\$\\s*([\\d.,]+)");

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Meses abreviados em pt-BR, como o Inter escreve ("abr.", "mai.", "ago."). */
    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1), Map.entry("fev", 2), Map.entry("mar", 3), Map.entry("abr", 4),
            Map.entry("mai", 5), Map.entry("jun", 6), Map.entry("jul", 7), Map.entry("ago", 8),
            Map.entry("set", 9), Map.entry("out", 10), Map.entry("nov", 11), Map.entry("dez", 12));

    @Override
    public InvoiceImportFormat format() {
        return InvoiceImportFormat.INTER_FATURA_PDF;
    }

    @Override
    public ParsedFatura parse(InputStream in) {
        return parseText(extractText(in));
    }

    /**
     * Texto puro do PDF, uma linha por linha visual.
     *
     * <p>{@code setSortByPosition(true)} não é detalhe: sem ele o PDFBox devolve o
     * texto na ordem do content stream, e uma tabela gerada por colunas sai com data,
     * descrição e valor embaralhados entre si.
     */
    public String extractText(InputStream in) {
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(in))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        } catch (IOException e) {
            throw new BusinessRuleException(
                    "Não foi possível ler o arquivo como PDF. Envie o PDF da fatura como o banco o gerou.");
        }
    }

    /** Parsing puro: recebe o texto já extraído e não faz I/O. */
    public ParsedFatura parseText(String rawText) {
        List<ImportRowError> errors = new ArrayList<>();
        // LinkedHashMap: a mesma seção reaberta na página seguinte cai no mesmo cartão,
        // e a ordem das seções continua a do PDF.
        Map<String, List<ParsedFaturaRow>> rowsByCard = new LinkedHashMap<>();

        String[] lines = rawText.split("\\R");
        boolean inExpenses = false;
        String currentCard = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            int lineNumber = i + 1;
            if (line.isEmpty()) {
                continue;
            }

            String lower = line.toLowerCase(Locale.ROOT);

            if (lower.startsWith(NEXT_INVOICE_HEADER)) {
                break;
            }
            if (lower.startsWith(EXPENSES_HEADER)) {
                inExpenses = true;
                currentCard = null;
                continue;
            }
            if (!inExpenses) {
                continue;
            }
            if (lower.startsWith(SECTION_TOTAL_PREFIX)) {
                currentCard = null;
                continue;
            }

            Matcher header = SECTION_HEADER.matcher(line);
            if (header.find()) {
                currentCard = header.group(1);
                rowsByCard.computeIfAbsent(currentCard, key -> new ArrayList<>());
                continue;
            }

            if (currentCard == null) {
                continue;
            }

            Matcher row = ROW.matcher(line);
            if (!row.matches()) {
                continue;
            }
            try {
                rowsByCard.get(currentCard).add(toRow(row, lineNumber));
            } catch (BusinessRuleException e) {
                errors.add(new ImportRowError(lineNumber, e.getMessage()));
            }
        }

        if (rowsByCard.isEmpty()) {
            throw new BusinessRuleException(
                    "Arquivo não reconhecido como fatura do Banco Inter: a seção "
                    + "\"Despesas da fatura\" com os blocos \"CARTÃO ****0000\" não foi encontrada.");
        }

        List<ParsedCardSection> sections = rowsByCard.entrySet().stream()
                .map(entry -> new ParsedCardSection(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();

        return new ParsedFatura(parseDueDate(rawText), parseTotalAmount(rawText), sections, errors);
    }

    // ── Linhas ────────────────────────────────────────────────────────────────

    private ParsedFaturaRow toRow(Matcher row, int lineNumber) {
        LocalDate date = parseDate(row.group(1), row.group(2), row.group(3));
        String description = row.group(4).trim();
        BigDecimal amount = parseAmount(row.group(6));
        if ("-".equals(row.group(5))) {
            amount = amount.negate();
        }

        Integer installmentNumber = null;
        Integer totalInstallments = null;
        Matcher installment = INSTALLMENT.matcher(description);
        if (installment.find()) {
            installmentNumber = Integer.valueOf(installment.group(1));
            totalInstallments = Integer.valueOf(installment.group(2));
        }

        return new ParsedFaturaRow(lineNumber, date, description, amount,
                installmentNumber, totalInstallments);
    }

    /** {@code "04", "abr", "2026"} — mês por abreviação pt-BR, não por {@code Locale}. */
    private LocalDate parseDate(String day, String month, String year) {
        String key = month.toLowerCase(Locale.ROOT);
        key = key.length() > 3 ? key.substring(0, 3) : key;
        Integer monthNumber = MONTHS.get(key);
        if (monthNumber == null) {
            throw new BusinessRuleException("Mês desconhecido: '" + month + "'.");
        }
        try {
            return LocalDate.of(Integer.parseInt(year), monthNumber, Integer.parseInt(day));
        } catch (RuntimeException e) {
            throw new BusinessRuleException(
                    "Data inválida: '" + day + " de " + month + " " + year + "'.");
        }
    }

    /** Valor pt-BR: ponto separa milhar, vírgula separa decimal. O sinal vem à parte. */
    private BigDecimal parseAmount(String value) {
        String normalized = value.replace(".", "").replace(',', '.').replace(" ", "");
        try {
            BigDecimal amount = new BigDecimal(normalized);
            if (amount.signum() == 0) {
                throw new BusinessRuleException("Valor zerado.");
            }
            return amount;
        } catch (NumberFormatException e) {
            throw new BusinessRuleException("Valor inválido: '" + value + "'.");
        }
    }

    // ── Cabeçalho ─────────────────────────────────────────────────────────────

    /**
     * O vencimento é o único dado do cabeçalho que o import realmente usa: é dele que
     * sai o mês de referência da fatura. Sem ele não dá para saber em qual fatura os
     * lançamentos entram, então a ausência derruba a leitura.
     */
    private LocalDate parseDueDate(String rawText) {
        Matcher header = INVOICE_HEADER.matcher(rawText);
        if (header.find()) {
            return parseSlashedDate(header.group(1));
        }
        Matcher labelled = DUE_DATE_LABEL.matcher(rawText);
        if (labelled.find()) {
            return parseSlashedDate(labelled.group(1));
        }
        throw new BusinessRuleException(
                "Não foi possível encontrar a data de vencimento no PDF da fatura.");
    }

    /** Informativo: se não der para ler com confiança, a prévia simplesmente não o mostra. */
    private BigDecimal parseTotalAmount(String rawText) {
        Matcher header = INVOICE_HEADER.matcher(rawText);
        if (header.find()) {
            return parseAmountOrNull(header.group(2));
        }
        Matcher labelled = TOTAL_AMOUNT_LABEL.matcher(rawText);
        return labelled.find() ? parseAmountOrNull(labelled.group(1)) : null;
    }

    private BigDecimal parseAmountOrNull(String value) {
        try {
            return parseAmount(value);
        } catch (BusinessRuleException e) {
            return null;
        }
    }

    private LocalDate parseSlashedDate(String value) {
        try {
            return LocalDate.parse(value, DATE_FORMAT);
        } catch (DateTimeParseException e) {
            throw new BusinessRuleException("Data de vencimento inválida: '" + value + "'.");
        }
    }
}
