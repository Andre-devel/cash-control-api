package com.cashcontrol.api.service;

/**
 * @author Andre
 * 04 de Novembro. de 2026
 */

import com.cashcontrol.api.domain.entity.SavingsGoal;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.request.SavingsGoalRequest;
import com.cashcontrol.api.dto.response.SavingsGoalResponse;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.FixedIncomeHoldingRepository;
import com.cashcontrol.api.repository.SavingsGoalRepository;
import com.cashcontrol.api.service.investment.InvestmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Metas de economia. O valor atual não é gravado: vem, a cada leitura, do saldo da conta vinculada
 * ou do valor líquido da aplicação vinculada. A meta não entra na projeção de saldo.
 */
@Service
@RequiredArgsConstructor
public class SavingsGoalService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final SavingsGoalRepository goalRepository;
    private final AccountRepository accountRepository;
    private final FixedIncomeHoldingRepository holdingRepository;
    private final AccountService accountService;
    private final InvestmentService investmentService;

    @Transactional(readOnly = true)
    public List<SavingsGoalResponse> list(UUID userId, boolean includeArchived, LocalDate today) {
        List<SavingsGoal> goals = includeArchived
                ? goalRepository.findAllByUserIdOrderByCreatedAtAsc(userId)
                : goalRepository.findAllByUserIdAndArchivedAtIsNullOrderByCreatedAtAsc(userId);
        Map<UUID, BigDecimal> holdingValues = investmentService.netValues(userId,
                goals.stream().map(SavingsGoal::getHoldingId).filter(Objects::nonNull).distinct().toList(), today);
        Map<UUID, BigDecimal> accountBalances = goals.stream().anyMatch(goal -> goal.getAccountId() != null)
                ? accountService.balancesByAccount(userId) : Map.of();
        return goals.stream().map(goal -> toResponse(goal, current(goal, accountBalances, holdingValues), today)).toList();
    }

    @Transactional(readOnly = true)
    public SavingsGoalResponse get(UUID id, UUID userId, LocalDate today) {
        return toResponse(findGoal(id, userId), today);
    }

    @Transactional
    public SavingsGoalResponse create(SavingsGoalRequest request, UUID userId, LocalDate today) {
        SavingsGoal goal = new SavingsGoal();
        goal.setUserId(userId);
        apply(goal, request, userId);
        return toResponse(goalRepository.save(goal), today);
    }

    @Transactional
    public SavingsGoalResponse update(UUID id, SavingsGoalRequest request, UUID userId, LocalDate today) {
        SavingsGoal goal = findGoal(id, userId);
        apply(goal, request, userId);
        return toResponse(goalRepository.save(goal), today);
    }

    /**
     * Apaga a meta sem progresso. A que já tem progresso é arquivada, para não sumir com o
     * histórico; apagar de novo a meta arquivada a remove de vez.
     */
    @Transactional
    public void delete(UUID id, UUID userId, LocalDate today) {
        SavingsGoal goal = findGoal(id, userId);
        if (!goal.isArchived() && current(goal, today).signum() > 0) {
            goal.setArchivedAt(Instant.now());
            goalRepository.save(goal);
            return;
        }
        goalRepository.delete(goal);
    }

    @Transactional
    public SavingsGoalResponse unarchive(UUID id, UUID userId, LocalDate today) {
        SavingsGoal goal = findGoal(id, userId);
        goal.setArchivedAt(null);
        return toResponse(goalRepository.save(goal), today);
    }

    /**
     * Quanto falta guardar por mês até a data: {@code (alvo − atual) / meses}, com os meses
     * contados por inteiro de hoje até a data e no mínimo 1 (data neste mês ou já passada pede o
     * restante de uma vez). Nulo sem data; zero quando a meta já foi atingida.
     */
    static BigDecimal monthlyNeeded(BigDecimal target, BigDecimal current, LocalDate targetDate, LocalDate today) {
        if (targetDate == null) {
            return null;
        }
        BigDecimal remaining = target.subtract(current);
        if (remaining.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        long months = Math.max(1, ChronoUnit.MONTHS.between(today, targetDate));
        return remaining.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
    }

    private void apply(SavingsGoal goal, SavingsGoalRequest request, UUID userId) {
        if (request.accountId() != null && request.holdingId() != null) {
            throw new BusinessRuleException("Vincule a meta a uma conta ou a uma aplicação, não às duas.");
        }
        if (request.accountId() != null && accountRepository.findByIdAndUserIdAndDeletedAtIsNull(
                request.accountId(), userId).isEmpty()) {
            throw new ResourceNotFoundException("Conta não encontrada.");
        }
        if (request.holdingId() != null && holdingRepository.findByIdAndUserIdAndDeletedAtIsNull(
                request.holdingId(), userId).isEmpty()) {
            throw new ResourceNotFoundException("Aplicação não encontrada.");
        }
        goal.setName(request.name().strip());
        goal.setTargetAmount(request.targetAmount());
        goal.setTargetDate(request.targetDate());
        goal.setAccountId(request.accountId());
        goal.setHoldingId(request.holdingId());
        goal.setIcon(request.icon());
        goal.setColor(request.color());
    }

    private SavingsGoal findGoal(UUID id, UUID userId) {
        return goalRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Meta não encontrada."));
    }

    private BigDecimal current(SavingsGoal goal, LocalDate today) {
        Map<UUID, BigDecimal> holdingValues = goal.getHoldingId() == null ? Map.of()
                : investmentService.netValues(goal.getUserId(), List.of(goal.getHoldingId()), today);
        Map<UUID, BigDecimal> accountBalances = goal.getAccountId() == null ? Map.of()
                : accountService.balancesByAccount(goal.getUserId());
        return current(goal, accountBalances, holdingValues);
    }

    /**
     * Saldo da conta (zero se apagada), valor líquido da aplicação (zero se resgatada ou apagada)
     * ou zero sem vínculo. A conta é apagada com {@code deleted_at}, então o {@code ON DELETE SET NULL}
     * da V53 não solta o vínculo: quem filtra é {@code accountBalances}, só com contas não apagadas.
     */
    private BigDecimal current(SavingsGoal goal, Map<UUID, BigDecimal> accountBalances,
                               Map<UUID, BigDecimal> holdingValues) {
        if (goal.getAccountId() != null) {
            return accountBalances.getOrDefault(goal.getAccountId(), BigDecimal.ZERO);
        }
        if (goal.getHoldingId() != null) {
            return holdingValues.getOrDefault(goal.getHoldingId(), BigDecimal.ZERO);
        }
        return BigDecimal.ZERO;
    }

    private SavingsGoalResponse toResponse(SavingsGoal goal, LocalDate today) {
        return toResponse(goal, current(goal, today), today);
    }

    private SavingsGoalResponse toResponse(SavingsGoal goal, BigDecimal current, LocalDate today) {
        BigDecimal percent = current.max(BigDecimal.ZERO).multiply(HUNDRED)
                .divide(goal.getTargetAmount(), 2, RoundingMode.HALF_UP);
        return new SavingsGoalResponse(goal.getId(), goal.getName(), goal.getTargetAmount(), goal.getTargetDate(),
                goal.getAccountId(), goal.getHoldingId(), goal.getIcon(), goal.getColor(), current, percent,
                monthlyNeeded(goal.getTargetAmount(), current, goal.getTargetDate(), today),
                goal.getCreatedAt(), goal.getArchivedAt());
    }
}
