package com.cashcontrol.api.service;

/**
 * @author Andre
 * 04 de Novembro. de 2026
 */

import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.response.SearchResponse;
import com.cashcontrol.api.dto.response.SearchResponse.Hit;
import com.cashcontrol.api.dto.response.SearchResponse.TransactionHit;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Busca global ({@code GET /api/v1/search}). Uma consulta por grupo, cada uma limitada a
 * {@link #LIMIT} linhas, só do usuário e sem registros apagados.
 *
 * <p>SQL direto em vez de seis métodos de repositório: é uma leitura só de projeção (id, título,
 * subtítulo), sem entidade a hidratar. O texto é comparado como {@code lower(coluna) LIKE
 * '%termo%'}, a mesma expressão dos índices trigram da V54.
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    static final int LIMIT = 10;

    /** Número em pt-BR: "615", "615,23", "1.234,56", com "R$" opcional na frente. */
    private static final Pattern PT_BR_NUMBER =
            Pattern.compile("(?:R\\$\\s*)?(\\d{1,3}(?:\\.\\d{3})+|\\d+)(?:,(\\d{1,2}))?");

    private static final Map<String, String> ACCOUNT_TYPE_LABELS = Map.of(
            "CHECKING", "Conta corrente",
            "SAVINGS", "Poupança",
            "CASH", "Dinheiro",
            "VIRTUAL_WALLET", "Carteira digital",
            "INTERNATIONAL", "Conta internacional",
            "JOINT", "Conta conjunta",
            "INVESTMENT", "Investimentos");

    private static final Map<String, String> FREQUENCY_LABELS = Map.of(
            "DAILY", "Diária",
            "WEEKLY", "Semanal",
            "BIWEEKLY", "Quinzenal",
            "MONTHLY", "Mensal",
            "YEARLY", "Anual");

    private final JdbcClient jdbcClient;

    /** {@code query} já validado no controller: ao menos 2 caracteres além dos espaços das pontas. */
    @Transactional(readOnly = true)
    public SearchResponse search(String query, UUID userId) {
        String trimmed = query.trim();
        String pattern = "%" + escapeLike(trimmed.toLowerCase(Locale.ROOT)) + "%";
        return new SearchResponse(
                transactions(userId, pattern, parseAmount(trimmed)),
                accounts(userId, pattern),
                creditCards(userId, pattern),
                categories(userId, pattern),
                recurrences(userId, pattern),
                installments(userId, pattern));
    }

    /** Valor exato digitado em pt-BR, ou {@code null} quando {@code q} não é número. */
    static BigDecimal parseAmount(String query) {
        var matcher = PT_BR_NUMBER.matcher(query.trim());
        if (!matcher.matches()) {
            return null;
        }
        String integer = matcher.group(1).replace(".", "");
        String fraction = matcher.group(2);
        return new BigDecimal(fraction == null ? integer : integer + "." + fraction);
    }

    private List<TransactionHit> transactions(UUID userId, String pattern, BigDecimal amount) {
        String amountClause = amount == null ? "" : " OR ABS(t.amount) = :amount";
        var statement = jdbcClient.sql("""
                        SELECT t.id, t.description, COALESCE(t.counterparty_name, a.name) AS subtitle,
                               t.competence_date, t.amount, t.type
                        FROM transactions t
                        JOIN accounts a ON a.id = t.account_id
                        WHERE t.user_id = :userId
                          AND a.deleted_at IS NULL
                          AND t.status <> 'CANCELLED'
                          AND (lower(t.description) LIKE :pattern
                               OR lower(t.counterparty_name) LIKE :pattern""" + amountClause + """
                        )
                        ORDER BY t.competence_date DESC, t.created_at DESC
                        LIMIT :limit
                        """)
                .param("userId", userId)
                .param("pattern", pattern)
                .param("limit", LIMIT);
        if (amount != null) {
            statement = statement.param("amount", amount);
        }
        return statement.query((rs, row) -> new TransactionHit(
                        rs.getObject("id", UUID.class),
                        rs.getString("description"),
                        rs.getString("subtitle"),
                        rs.getObject("competence_date", LocalDate.class),
                        rs.getBigDecimal("amount"),
                        TransactionType.valueOf(rs.getString("type"))))
                .list();
    }

    private List<Hit> accounts(UUID userId, String pattern) {
        return jdbcClient.sql("""
                        SELECT id, name, type, description
                        FROM accounts
                        WHERE user_id = :userId
                          AND deleted_at IS NULL
                          AND lower(name) LIKE :pattern
                        ORDER BY archived_at NULLS FIRST, sort_order, name
                        LIMIT :limit
                        """)
                .param("userId", userId)
                .param("pattern", pattern)
                .param("limit", LIMIT)
                .query((rs, row) -> {
                    String description = rs.getString("description");
                    String subtitle = description != null && !description.isBlank()
                            ? description
                            : ACCOUNT_TYPE_LABELS.getOrDefault(rs.getString("type"), rs.getString("type"));
                    return new Hit(rs.getObject("id", UUID.class), rs.getString("name"), subtitle);
                })
                .list();
    }

    private List<Hit> creditCards(UUID userId, String pattern) {
        return jdbcClient.sql("""
                        SELECT id, name, issuer, last4_digits
                        FROM credit_cards
                        WHERE user_id = :userId
                          AND deleted_at IS NULL
                          AND (lower(name) LIKE :pattern OR lower(issuer) LIKE :pattern)
                        ORDER BY archived_at NULLS FIRST, name
                        LIMIT :limit
                        """)
                .param("userId", userId)
                .param("pattern", pattern)
                .param("limit", LIMIT)
                .query((rs, row) -> new Hit(rs.getObject("id", UUID.class), rs.getString("name"),
                        cardSubtitle(rs.getString("issuer"), rs.getString("last4_digits"))))
                .list();
    }

    /**
     * Categorias visíveis ao usuário: as dele, não arquivadas, e as do sistema que ele não ocultou
     * (nem a categoria, nem a categoria pai).
     */
    private List<Hit> categories(UUID userId, String pattern) {
        return jdbcClient.sql("""
                        SELECT c.id, c.name, p.name AS parent_name
                        FROM categories c
                        LEFT JOIN categories p ON p.id = c.parent_id
                        WHERE (c.user_id = :userId
                               OR (c.user_id IS NULL
                                   AND NOT EXISTS (SELECT 1 FROM hidden_system_categories h
                                                   WHERE h.user_id = :userId
                                                     AND h.category_id IN (c.id, c.parent_id))))
                          AND c.archived_at IS NULL
                          AND NOT c.is_archived
                          AND lower(c.name) LIKE :pattern
                        ORDER BY c.parent_id NULLS FIRST, c.name
                        LIMIT :limit
                        """)
                .param("userId", userId)
                .param("pattern", pattern)
                .param("limit", LIMIT)
                .query((rs, row) -> new Hit(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("parent_name")))
                .list();
    }

    private List<Hit> recurrences(UUID userId, String pattern) {
        return jdbcClient.sql("""
                        SELECT r.id, r.description, r.frequency, a.name AS account_name
                        FROM recurrence_rules r
                        JOIN accounts a ON a.id = r.account_id
                        WHERE r.user_id = :userId
                          AND r.deleted_at IS NULL
                          AND r.status <> 'DELETED'
                          AND lower(r.description) LIKE :pattern
                        ORDER BY r.description
                        LIMIT :limit
                        """)
                .param("userId", userId)
                .param("pattern", pattern)
                .param("limit", LIMIT)
                .query((rs, row) -> new Hit(rs.getObject("id", UUID.class), rs.getString("description"),
                        FREQUENCY_LABELS.getOrDefault(rs.getString("frequency"), rs.getString("frequency"))
                                + " · " + rs.getString("account_name")))
                .list();
    }

    private List<Hit> installments(UUID userId, String pattern) {
        return jdbcClient.sql("""
                        SELECT id, description, total_installments, is_settled
                        FROM installment_series
                        WHERE user_id = :userId
                          AND lower(description) LIKE :pattern
                        ORDER BY first_payment_date DESC
                        LIMIT :limit
                        """)
                .param("userId", userId)
                .param("pattern", pattern)
                .param("limit", LIMIT)
                .query((rs, row) -> new Hit(rs.getObject("id", UUID.class), rs.getString("description"),
                        rs.getInt("total_installments") + " parcelas"
                                + (rs.getBoolean("is_settled") ? " · quitado" : "")))
                .list();
    }

    private static String cardSubtitle(String issuer, String last4) {
        boolean hasIssuer = issuer != null && !issuer.isBlank();
        boolean hasLast4 = last4 != null && !last4.isBlank();
        if (hasIssuer && hasLast4) {
            return issuer + " · final " + last4;
        }
        if (hasIssuer) {
            return issuer;
        }
        return hasLast4 ? "final " + last4 : null;
    }

    /** Escapa os curingas do LIKE (o escape padrão do PostgreSQL é a barra invertida). */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
