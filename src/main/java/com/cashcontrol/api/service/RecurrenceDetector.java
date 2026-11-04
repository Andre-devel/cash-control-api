package com.cashcontrol.api.service;

/**
 * @author Andre
 * 4 de Novembro. de 2026
 */

import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.response.RecurrenceSuggestionResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Detecta despesas que se repetem todo mês e ainda não viraram recorrência. Funções puras: quem chama
 * carrega os lançamentos e as recorrências ativas do usuário.
 */
public final class RecurrenceDetector {

    /** Janela de histórico considerada, em meses contados a partir de hoje. */
    public static final int WINDOW_MONTHS = 6;
    /** Meses distintos em que a despesa precisa aparecer para virar sugestão. */
    public static final int MIN_MONTHS = 3;
    /** Desvio máximo do valor em relação à mediana do grupo. */
    public static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.05");

    private static final int MAX_PATTERN_WORDS = 3;
    private static final Pattern DATE = Pattern.compile("\\d{1,4}\\s*[/.-]\\s*\\d{1,2}(\\s*[/.-]\\s*\\d{2,4})?");
    private static final Pattern NON_LETTER = Pattern.compile("[^\\p{L}\\s]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Set<String> STOP_WORDS = Set.of(
            "de", "da", "do", "das", "dos", "e", "em", "no", "na", "nos", "nas",
            "com", "para", "por", "a", "o", "as", "os");

    private RecurrenceDetector() {
    }

    /**
     * Descrição reduzida ao que identifica o gasto: minúsculas, sem datas, parcelas ("3/10"), dígitos,
     * {@code *} e pontuação, e só as {@value #MAX_PATTERN_WORDS} primeiras palavras significativas.
     * Devolve vazio quando nada sobra.
     */
    public static String normalize(String description) {
        if (description == null) {
            return "";
        }
        String text = description.toLowerCase(Locale.ROOT);
        text = DATE.matcher(text).replaceAll(" ");
        text = NON_LETTER.matcher(text).replaceAll(" ");
        return Arrays.stream(SPACES.split(text.strip()))
                .filter(word -> word.length() > 1 && !STOP_WORDS.contains(word))
                .limit(MAX_PATTERN_WORDS)
                .collect(Collectors.joining(" "));
    }

    /**
     * Agrupa as despesas pagas dos últimos {@value #WINDOW_MONTHS} meses pela descrição normalizada e
     * devolve os grupos presentes em ao menos {@value #MIN_MONTHS} meses distintos com valor a até 5% da
     * mediana. Transferências, pagamentos de fatura, reembolsos e grupos que já têm recorrência ativa
     * (descrição normalizada igual) ficam de fora. Mais recentes primeiro.
     */
    public static List<RecurrenceSuggestionResponse> detect(Collection<Transaction> transactions,
                                                            Collection<String> activeRecurrenceDescriptions,
                                                            LocalDate today) {
        Set<String> covered = activeRecurrenceDescriptions.stream()
                .map(RecurrenceDetector::normalize)
                .collect(Collectors.toSet());
        LocalDate from = today.minusMonths(WINDOW_MONTHS);

        Map<String, List<Transaction>> groups = new LinkedHashMap<>();
        for (Transaction t : transactions) {
            if (!isCandidate(t, from, today)) {
                continue;
            }
            String pattern = normalize(t.getDescription());
            if (pattern.isEmpty() || covered.contains(pattern)) {
                continue;
            }
            groups.computeIfAbsent(pattern, k -> new ArrayList<>()).add(t);
        }

        return groups.entrySet().stream()
                .map(entry -> toSuggestion(entry.getKey(), entry.getValue()))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(RecurrenceSuggestionResponse::lastDate).reversed()
                        .thenComparing(RecurrenceSuggestionResponse::pattern))
                .toList();
    }

    private static boolean isCandidate(Transaction t, LocalDate from, LocalDate today) {
        return t.getType() == TransactionType.EXPENSE
                && t.getStatus() == TransactionStatus.PAID
                && t.getCancelledAt() == null
                && t.getTransferGroupId() == null
                && t.getPaidInvoice() == null
                && !t.isReimbursable()
                && t.getAmount() != null
                && t.getCompetenceDate() != null
                && !t.getCompetenceDate().isBefore(from)
                && !t.getCompetenceDate().isAfter(today);
    }

    private static RecurrenceSuggestionResponse toSuggestion(String pattern, List<Transaction> group) {
        BigDecimal median = median(group.stream().map(Transaction::getAmount).toList());
        BigDecimal maxDeviation = median.multiply(AMOUNT_TOLERANCE);
        List<Transaction> inBand = group.stream()
                .filter(t -> t.getAmount().subtract(median).abs().compareTo(maxDeviation) <= 0)
                .toList();
        long months = inBand.stream().map(t -> YearMonth.from(t.getCompetenceDate())).distinct().count();
        if (months < MIN_MONTHS) {
            return null;
        }
        Transaction last = inBand.stream()
                .max(Comparator.comparing(Transaction::getCompetenceDate)
                        .thenComparing(Transaction::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElseThrow();
        return new RecurrenceSuggestionResponse(
                pattern,
                last.getDescription(),
                median(inBand.stream().map(Transaction::getAmount).toList()),
                last.getCompetenceDate().getDayOfMonth(),
                (int) months,
                last.getCompetenceDate(),
                last.getCategory() != null ? last.getCategory().getId() : null,
                last.getAccount() != null ? last.getAccount().getId() : null,
                last.getCreditCard() != null ? last.getCreditCard().getId() : null);
    }

    private static BigDecimal median(List<BigDecimal> values) {
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int size = sorted.size();
        BigDecimal middle = sorted.get(size / 2);
        if (size % 2 == 1) {
            return middle.setScale(2, RoundingMode.HALF_UP);
        }
        return sorted.get(size / 2 - 1).add(middle).divide(BigDecimal.TWO, 2, RoundingMode.HALF_UP);
    }
}
