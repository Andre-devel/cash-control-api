package com.cashcontrol.api.service.budget;

/**
 * @author Andre
 * 31 de Outubro. de 2026
 */

import com.cashcontrol.api.domain.entity.PushSubscription;
import com.cashcontrol.api.dto.response.BudgetProgressResponse;
import com.cashcontrol.api.repository.CategoryBudgetRepository;
import com.cashcontrol.api.repository.PushSubscriptionRepository;
import com.cashcontrol.api.service.push.PushService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Aviso por push quando uma categoria chega a 80% do teto e quando passa de 100%, uma vez por
 * faixa, categoria e mês. Roda pelo agendador em vez de em cada gravação: o gasto muda por muitos
 * caminhos (lançamento, importação, fila do banco, recorrência) e um atraso de minutos não pesa.
 */
@Service
@RequiredArgsConstructor
public class BudgetAlertService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final int WARN = 80;
    static final int OVER = 100;

    private final BudgetService budgetService;
    private final CategoryBudgetRepository budgetRepository;
    private final PushSubscriptionRepository subscriptionRepository;
    private final PushService pushService;

    public List<UUID> usersToCheck() {
        return subscriptionRepository.findUserIdsWithBudgetAlerts();
    }

    /** @return quantos avisos novos saíram para o usuário */
    @Transactional
    public int check(UUID userId, LocalDate today) {
        YearMonth month = YearMonth.from(today);
        BudgetProgressResponse progress = budgetService.progress(userId, month, today);
        int sent = 0;
        for (BudgetProgressResponse.Item item : progress.items()) {
            // Marca as duas faixas; quem pula direto para 100% recebe só o aviso de 100%.
            boolean overNew = crossed(item, OVER) && mark(userId, item, month, OVER);
            boolean warnNew = crossed(item, WARN) && mark(userId, item, month, WARN);
            if (overNew || warnNew) {
                pushService.send(userId, subscription -> payload(item, overNew, subscription));
                sent++;
            }
        }
        return sent;
    }

    private static boolean crossed(BudgetProgressResponse.Item item, int threshold) {
        return threshold == OVER
                ? item.percent().compareTo(BigDecimal.valueOf(OVER)) > 0
                : item.percent().compareTo(BigDecimal.valueOf(WARN)) >= 0;
    }

    private boolean mark(UUID userId, BudgetProgressResponse.Item item, YearMonth month, int threshold) {
        return budgetRepository.markAlertSent(userId, item.categoryId(), month.atDay(1), threshold) == 1;
    }

    private static String payload(BudgetProgressResponse.Item item, boolean over, PushSubscription subscription) {
        if (!subscription.isBudgetAlerts()) {
            return null;
        }
        String title = item.name() + (over ? ": passou do orçamento" : ": "
                + item.percent().setScale(0, RoundingMode.DOWN).toPlainString() + "% do orçamento");
        String body;
        if (subscription.isHideAmounts()) {
            body = "Toque para ver o orçamento do mês.";
        } else if (over) {
            body = "Passou " + money(item.remaining().negate()) + " do teto de " + money(item.budget()) + ".";
        } else {
            body = "Falta " + money(item.remaining()) + " de " + money(item.budget()) + ".";
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "budget");
        payload.put("categoryId", item.categoryId().toString());
        payload.put("title", title);
        payload.put("body", body);
        return JSON.writeValueAsString(payload);
    }

    private static String money(BigDecimal value) {
        return NumberFormat.getCurrencyInstance(Locale.of("pt", "BR")).format(value);
    }
}
