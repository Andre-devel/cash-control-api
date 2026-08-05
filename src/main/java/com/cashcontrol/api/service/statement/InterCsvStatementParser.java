package com.cashcontrol.api.service.statement;

import com.cashcontrol.api.domain.entity.StatementFormat;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.dto.response.ImportRowError;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Extrato de conta corrente do Banco Inter em CSV.
 *
 * <p>Formato real, com bloco de cabeçalho antes das colunas:
 *
 * <pre>
 *  Extrato Conta Corrente
 * Conta ;123456789
 * Período ;06/08/2024 a 05/08/2026
 * Saldo ;1.362,84
 *
 * Data Lançamento;Histórico;Descrição;Valor;Saldo
 * 04/08/2026;Pix enviado ;Pix Marketplace;-144,06;1.362,84
 * </pre>
 *
 * <p>A coluna {@code Saldo} é ignorada de propósito: no arquivo real ela não
 * fecha com a sequência de valores, então não serve nem para conciliação nem
 * para validação.
 *
 * <p>Leitura manual em vez de biblioteca de CSV: o formato não usa aspas nem
 * escape, e o projeto roda {@code dependencyCheckAnalyze} no build — dependência
 * nova é superfície de CVE que este parser de 100 linhas não justifica.
 */
@Component
public class InterCsvStatementParser implements StatementParser {

    private static final char SEPARATOR = ';';
    private static final String BOM = "\uFEFF";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final String HEADER_FIRST_COLUMN = "data lancamento";
    private static final String PREAMBLE_ACCOUNT = "conta";
    private static final String PREAMBLE_PERIOD = "periodo";

    /** Colunas do corpo: data, histórico, descrição, valor, saldo. */
    private static final int EXPECTED_COLUMNS = 5;

    private static final Pattern COLLAPSIBLE_WHITESPACE = Pattern.compile("\\s{2,}");

    @Override
    public StatementFormat format() {
        return StatementFormat.INTER_CSV;
    }

    @Override
    public ParsedStatement parse(InputStream in) {
        List<ParsedStatementRow> rows = new ArrayList<>();
        List<ImportRowError> errors = new ArrayList<>();

        String sourceAccountLabel = null;
        LocalDate periodStart = null;
        LocalDate periodEnd = null;
        boolean headerFound = false;
        int lineNumber = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber == 1) {
                    line = stripBom(line);
                }
                if (line.isBlank()) {
                    continue;
                }

                if (!headerFound) {
                    // O bloco de cabeçalho não tem tamanho fixo entre exports, então a
                    // fronteira é a própria linha de colunas, não uma contagem de linhas.
                    String[] preamble = split(line);
                    String label = StatementHistoryMapper.normalize(preamble[0]);
                    if (label.startsWith(HEADER_FIRST_COLUMN)) {
                        headerFound = true;
                    } else if (label.equals(PREAMBLE_ACCOUNT) && preamble.length > 1) {
                        sourceAccountLabel = preamble[1].trim();
                    } else if (label.equals(PREAMBLE_PERIOD) && preamble.length > 1) {
                        LocalDate[] period = parsePeriod(preamble[1]);
                        periodStart = period[0];
                        periodEnd = period[1];
                    }
                    continue;
                }

                try {
                    rows.add(parseRow(split(line), lineNumber));
                } catch (BusinessRuleException e) {
                    errors.add(new ImportRowError(lineNumber, e.getMessage()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        if (!headerFound) {
            throw new BusinessRuleException(
                    "Arquivo não reconhecido como extrato do Banco Inter: a linha de colunas "
                    + "\"Data Lançamento;Histórico;Descrição;Valor;Saldo\" não foi encontrada.");
        }

        return new ParsedStatement(sourceAccountLabel, periodStart, periodEnd, rows, errors);
    }

    private ParsedStatementRow parseRow(String[] columns, int lineNumber) {
        if (columns.length < EXPECTED_COLUMNS) {
            throw new BusinessRuleException(
                    "Esperadas " + EXPECTED_COLUMNS + " colunas, encontradas " + columns.length + ".");
        }

        LocalDate date = parseDate(columns[0].trim());
        String history = columns[1].trim();

        // Uma descrição com ';' dentro empurraria as colunas para a direita. Data e
        // histórico são sempre os dois primeiros campos, valor e saldo sempre os dois
        // últimos; o que sobra no meio é a descrição, inclusive os ';' dela.
        String description = String.join(
                String.valueOf(SEPARATOR),
                List.of(columns).subList(2, columns.length - 2));

        // O extrato alinha a descrição em colunas de largura fixa
        // ("Dias E Damasceno Ltda  Penapolis     Bra"), e esse enchimento não é
        // informação: iria para o banco e apareceria assim na tela. Não afeta a chave
        // de deduplicação — o StatementRowHasher já normaliza espaços antes do hash.
        description = COLLAPSIBLE_WHITESPACE.matcher(description).replaceAll(" ").trim();

        if (description.isEmpty()) {
            description = history;
        }

        BigDecimal signedAmount = parseAmount(columns[columns.length - 2].trim());

        return new ParsedStatementRow(lineNumber, date, history, description, signedAmount);
    }

    private LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value, DATE_FORMAT);
        } catch (DateTimeParseException e) {
            throw new BusinessRuleException("Data inválida: '" + value + "'. Esperado dd/mm/aaaa.");
        }
    }

    /** Valor pt-BR: ponto separa milhar, vírgula separa decimal, sinal negativo é saída. */
    private BigDecimal parseAmount(String value) {
        String normalized = value.replace(".", "").replace(',', '.').replace(" ", "");
        if (normalized.isEmpty()) {
            throw new BusinessRuleException("Valor ausente.");
        }
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

    /** {@code "06/08/2024 a 05/08/2026"} — meramente informativo, nunca derruba a leitura. */
    private LocalDate[] parsePeriod(String value) {
        String[] parts = value.trim().split("\\s+a\\s+");
        if (parts.length != 2) {
            return new LocalDate[]{null, null};
        }
        try {
            return new LocalDate[]{
                    LocalDate.parse(parts[0].trim(), DATE_FORMAT),
                    LocalDate.parse(parts[1].trim(), DATE_FORMAT)
            };
        } catch (DateTimeParseException e) {
            return new LocalDate[]{null, null};
        }
    }

    private String[] split(String line) {
        return line.split(String.valueOf(SEPARATOR), -1);
    }

    private String stripBom(String line) {
        return line.startsWith(BOM) ? line.substring(1) : line;
    }
}
