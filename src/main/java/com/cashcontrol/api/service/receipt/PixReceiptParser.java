package com.cashcontrol.api.service.receipt;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê os campos de um comprovante de PIX a partir do texto já extraído do arquivo.
 *
 * <p>Um parser heurístico único, não um por banco como na fatura: a fatura declara o
 * formato porque o usuário escolhe o banco na tela; o comprovante compartilhado não vem
 * com essa informação, e cada banco desenha o próprio layout. As âncoras abaixo miram nos
 * rótulos que se repetem entre os principais emissores (Nubank, Inter, PicPay, Itaú),
 * aceitando que nem todo comprovante vai casar com todas — daí {@link ParsedReceipt} ser
 * inteiramente opcional e o fluxo sempre pedir revisão do usuário.
 *
 * <p>Parsing puro, sem I/O: recebe o texto já extraído, como
 * {@code InterFaturaPdfParser.parseText}, para poder ser testado com texto sintético.
 */
@Component
public class PixReceiptParser {

    /** {@code R$ 1.234,56} — captura o valor perto de um rótulo de "valor". */
    private static final Pattern AMOUNT_NEAR_LABEL = Pattern.compile(
            "(?i)valor\\s*(?:da\\s*transfer[êe]ncia|pago|total)?\\s*[:\\-]?\\s*R\\$\\s*([\\d.,]+)");

    /** Fallback: o primeiro "R$ ..." do texto, quando nenhum rótulo de valor casou. */
    private static final Pattern ANY_AMOUNT = Pattern.compile("R\\$\\s*([\\d.,]+)");

    private static final Pattern DATE_NEAR_LABEL = Pattern.compile(
            "(?i)data\\s*(?:do\\s*pagamento|e\\s*hora|/\\s*hora)?\\s*[:\\-]?\\s*(\\d{2}/\\d{2}/\\d{4})");

    private static final Pattern ANY_DATE = Pattern.compile("(\\d{2}/\\d{2}/\\d{4})");

    /**
     * {@code E} + 8 dígitos (ISPB) + 12 dígitos (data/hora) + 11 alfanuméricos — o formato
     * fixo de 32 caracteres do identificador fim-a-fim definido pelo Banco Central. Não
     * depende de rótulo: aparece sozinho na maioria dos comprovantes.
     */
    private static final Pattern END_TO_END_ID = Pattern.compile("\\bE\\d{8}\\d{12}[A-Za-z0-9]{11}\\b");

    private static final Pattern END_TO_END_ID_LABELED = Pattern.compile(
            "(?i)(?:id\\s*da\\s*transa[çc][ãa]o|identificador)\\s*[:\\-]?\\s*([A-Za-z0-9]{20,40})");

    /**
     * CPF ({@code 000.000.000-00}) ou CNPJ ({@code 00.000.000/0000-00}), inclusive
     * mascarado com asterisco ({@code ***.456.789-**}, como os bancos costumam exibir).
     * A pontuação é exigida (não opcional): sem {@code \b} nas pontas — o mascaramento
     * costuma começar com asterisco, um caractere não-alfanumérico, e {@code \b} não marca
     * fronteira entre dois não-alfanuméricos — a estrutura de pontos e traço já é
     * específica o bastante para não casar por acidente com outra coisa.
     */
    private static final Pattern DOCUMENT = Pattern.compile(
            "([\\d*]{3}\\.[\\d*]{3}\\.[\\d*]{3}-[\\d*]{2}"
            + "|[\\d*]{2}\\.[\\d*]{3}\\.[\\d*]{3}/[\\d*]{4}-[\\d*]{2})");

    private static final Pattern RECIPIENT_LABEL = Pattern.compile(
            "(?i)^\\s*(?:para|recebedor|destinat[áa]rio|quem\\s*recebeu|nome)\\s*[:\\-]?\\s*(.*)$");

    private static final Pattern INSTITUTION_LABEL = Pattern.compile(
            "(?i)^\\s*institui[çc][ãa]o\\s*(?:de\\s*destino)?\\s*[:\\-]?\\s*(.+)$");

    private static final Pattern MESSAGE_LABEL = Pattern.compile(
            "(?i)^\\s*(?:mensagem|descri[çc][ãa]o)\\s*[:\\-]?\\s*(.+)$");

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public ParsedReceipt parseText(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return ParsedReceipt.EMPTY;
        }

        String[] lines = rawText.split("\\R");

        return new ParsedReceipt(
                findAmount(rawText),
                findDate(rawText),
                findRecipientName(lines),
                findFirst(DOCUMENT, rawText),
                findEndToEndId(rawText),
                findLabeled(lines, INSTITUTION_LABEL),
                findLabeled(lines, MESSAGE_LABEL));
    }

    private BigDecimal findAmount(String text) {
        String raw = findFirst(AMOUNT_NEAR_LABEL, text);
        if (raw == null) {
            raw = findFirst(ANY_AMOUNT, text);
        }
        if (raw == null) {
            return null;
        }
        try {
            BigDecimal amount = new BigDecimal(raw.replace(".", "").replace(',', '.'));
            return amount.signum() > 0 ? amount : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDate findDate(String text) {
        String raw = findFirst(DATE_NEAR_LABEL, text);
        if (raw == null) {
            raw = findFirst(ANY_DATE, text);
        }
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw, DATE_FORMAT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String findEndToEndId(String text) {
        Matcher unlabeled = END_TO_END_ID.matcher(text);
        if (unlabeled.find()) {
            return unlabeled.group();
        }
        return findFirst(END_TO_END_ID_LABELED, text);
    }

    /**
     * O nome de quem recebeu. Tenta o rótulo primeiro; sem ele, a linha imediatamente
     * acima de um CPF/CNPJ — comprovantes costumam desenhar "Nome" seguido do documento
     * na linha de baixo, sem um rótulo explícito de "Recebedor" na frente.
     */
    private String findRecipientName(String[] lines) {
        for (String line : lines) {
            Matcher label = RECIPIENT_LABEL.matcher(line.trim());
            if (label.matches() && !label.group(1).isBlank()) {
                return clean(label.group(1));
            }
        }
        for (int i = 1; i < lines.length; i++) {
            if (DOCUMENT.matcher(lines[i].trim()).find()) {
                String candidate = clean(lines[i - 1]);
                if (!candidate.isEmpty() && !DOCUMENT.matcher(candidate).find()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private String findLabeled(String[] lines, Pattern labelPattern) {
        for (String line : lines) {
            Matcher label = labelPattern.matcher(line.trim());
            if (label.matches() && !label.group(1).isBlank()) {
                return clean(label.group(1));
            }
        }
        return null;
    }

    private String findFirst(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String clean(String value) {
        return value.strip();
    }
}
