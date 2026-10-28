package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.domain.entity.DeleteRecurrenceStrategy;
import com.cashcontrol.api.domain.entity.PaymentMethod;
import com.cashcontrol.api.domain.entity.CreditCard;
import com.cashcontrol.api.domain.entity.RecurrenceRule;
import com.cashcontrol.api.domain.entity.RecurrenceStatus;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.request.CreateRecurrenceRequest;
import com.cashcontrol.api.dto.request.EditRecurrenceRequest;
import com.cashcontrol.api.dto.request.PauseRecurrenceRequest;
import com.cashcontrol.api.dto.response.DeleteRecurrenceResult;
import com.cashcontrol.api.dto.response.EditRecurrenceResult;
import com.cashcontrol.api.dto.response.RecurrenceCreationResponse;
import com.cashcontrol.api.dto.response.RecurrenceRuleResponse;
import com.cashcontrol.api.dto.response.TransactionDetailResponse;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.RecurrenceRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RecurrenceServiceImpl implements RecurrenceService {

    private static final int LOOKAHEAD_PERIODS = 12;
    private static final List<TransactionStatus> CANCELLABLE_STATUSES =
            List.of(TransactionStatus.PENDING, TransactionStatus.OVERDUE);

    private final RecurrenceRepository recurrenceRepository;
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final CategoryResolver categoryResolver;
    private final RecurrenceGeneratorService generatorService;
    private final TransactionService transactionService;
    private final CreditCardService creditCardService;

    @Override
    @Transactional
    public RecurrenceCreationResponse createRecurrence(CreateRecurrenceRequest request, UUID userId) {
        Account account = accountRepository.findByIdAndUserIdAndDeletedAtIsNull(request.accountId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + request.accountId()));

        if (account.getArchivedAt() != null) {
            throw new BusinessRuleException("Não é possível criar uma recorrência em uma conta arquivada.");
        }

        if (request.type() != TransactionType.INCOME && request.type() != TransactionType.EXPENSE
                && request.type() != TransactionType.REFUND) {
            throw new BusinessRuleException("Recorrências só podem gerar receitas, despesas ou reembolsos.");
        }

        Category category = categoryResolver.resolve(request.categoryId(), userId);
        Category subcategory = categoryResolver.resolveSubcategory(request.subcategoryId(), category, userId);

        PaymentMethod paymentMethod = transactionService.resolvePaymentMethod(request.paymentMethod());
        CreditCard creditCard = transactionService.validateAndResolveCreditCard(
                request.creditCardId(), paymentMethod.getSlug(), userId);
        if (creditCard != null && request.type() == TransactionType.INCOME) {
            throw new BusinessRuleException("Receitas não podem ser lançadas no cartão de crédito.");
        }
        if (request.endDate() != null && request.endDate().isBefore(request.startDate())) {
            throw new BusinessRuleException("A data de fim não pode ser anterior à data de início.");
        }

        RecurrenceRule rule = new RecurrenceRule();
        rule.setUserId(userId);
        rule.setAccount(account);
        rule.setType(request.type());
        rule.setFrequency(request.frequency());
        rule.setStatus(RecurrenceStatus.ACTIVE);
        rule.setAmount(request.amount());
        rule.setDescription(request.description());
        rule.setCategory(category);
        rule.setSubcategory(subcategory);
        rule.setStartDate(request.startDate());
        rule.setEndDate(request.endDate());
        rule.setPaymentMethod(paymentMethod);
        rule.setCreditCard(creditCard);

        LocalDate firstDate = request.startDate();
        rule.setNextOccurrenceDate(firstDate);

        rule = recurrenceRepository.save(rule);

        // Create first instance immediately
        Transaction firstInstance = buildTransaction(rule, firstDate);
        firstInstance = transactionRepository.save(firstInstance);
        creditCardService.createInvoiceItemForTransaction(firstInstance);

        // Pre-generate up to LOOKAHEAD_PERIODS future instances
        LocalDate nextDate = generatorService.nextOccurrence(firstDate, firstDate, request.frequency());
        List<Transaction> upcoming = new ArrayList<>();
        int generated = 1;

        while (generated < LOOKAHEAD_PERIODS) {
            if (rule.getEndDate() != null && nextDate.isAfter(rule.getEndDate())) {
                break;
            }
            upcoming.add(buildTransaction(rule, nextDate));
            nextDate = generatorService.nextOccurrence(firstDate, nextDate, request.frequency());
            generated++;
        }

        if (!upcoming.isEmpty()) {
            transactionRepository.saveAll(upcoming).forEach(creditCardService::createInvoiceItemForTransaction);
        }

        // Advance nextOccurrenceDate to after last pre-generated instance
        rule.setNextOccurrenceDate(nextDate);
        if (rule.getEndDate() != null && nextDate.isAfter(rule.getEndDate())) {
            rule.setStatus(RecurrenceStatus.ENDED);
            rule.setNextOccurrenceDate(null);
        }
        rule = recurrenceRepository.save(rule);

        return new RecurrenceCreationResponse(toRuleResponse(rule), transactionService.toDetail(firstInstance));
    }

    @Override
    @Transactional
    public EditRecurrenceResult editSeries(UUID ruleId, EditRecurrenceRequest request, UUID userId) {
        RecurrenceRule rule = findOwnedRule(ruleId, userId);

        if (rule.getStatus() == RecurrenceStatus.DELETED) {
            throw new BusinessRuleException("Não é possível editar uma recorrência excluída.");
        }

        Account account = null;
        if (request.accountId() != null) {
            account = accountRepository.findByIdAndUserIdAndDeletedAtIsNull(request.accountId(), userId)
                    .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + request.accountId()));
            if (account.getArchivedAt() != null) {
                throw new BusinessRuleException("Não é possível mover a recorrência para uma conta arquivada.");
            }
        }

        Category category = categoryResolver.resolve(request.categoryId(), userId);
        Category subcategory = categoryResolver.resolveSubcategory(request.subcategoryId(), category, userId);

        // Update master rule
        if (request.amount() != null) rule.setAmount(request.amount());
        if (request.description() != null) rule.setDescription(request.description());
        if (request.categoryId() != null) {
            rule.setCategory(category);
            rule.setSubcategory(subcategory);
        }
        if (account != null) rule.setAccount(account);
        rule = recurrenceRepository.save(rule);

        // Update all future PENDING/OVERDUE instances
        List<Transaction> futures = transactionRepository.findAllByRecurrenceRule_IdAndStatusIn(
                ruleId, CANCELLABLE_STATUSES);

        LocalDate today = LocalDate.now();
        for (Transaction tx : futures) {
            if (tx.getCompetenceDate().isBefore(today)) continue;
            if (request.amount() != null) tx.setAmount(request.amount());
            if (request.description() != null) tx.setDescription(request.description());
            if (request.categoryId() != null) {
                tx.setCategory(category);
                tx.setSubcategory(subcategory);
            }
            if (account != null) tx.setAccount(account);
        }

        List<Transaction> updated = futures.stream()
                .filter(tx -> !tx.getCompetenceDate().isBefore(today))
                .collect(Collectors.toList());

        transactionRepository.saveAll(updated);
        updated.forEach(creditCardService::syncInvoiceItemForTransaction);

        return new EditRecurrenceResult(toRuleResponse(rule), updated.size());
    }

    @Override
    @Transactional
    public RecurrenceRuleResponse pauseRecurrence(UUID ruleId, PauseRecurrenceRequest request, UUID userId) {
        RecurrenceRule rule = findOwnedRule(ruleId, userId);

        if (rule.getStatus() != RecurrenceStatus.ACTIVE) {
            throw new BusinessRuleException("Apenas recorrências ATIVAS podem ser pausadas. Status atual: " + rule.getStatus());
        }

        rule.setStatus(RecurrenceStatus.PAUSED);
        rule.setPausedAt(Instant.now());
        if (request.resumeAt() != null) {
            rule.setResumeAt(request.resumeAt().atStartOfDay().toInstant(java.time.ZoneOffset.UTC));
        }

        // Cancel all future PENDING/OVERDUE instances
        LocalDate today = LocalDate.now();
        List<Transaction> pending = transactionRepository.findAllByRecurrenceRule_IdAndStatusIn(
                ruleId, CANCELLABLE_STATUSES);

        Instant now = Instant.now();
        List<Transaction> toCancelFuture = pending.stream()
                .filter(tx -> !tx.getCompetenceDate().isBefore(today))
                .collect(Collectors.toList());

        for (Transaction tx : toCancelFuture) {
            tx.setStatus(TransactionStatus.CANCELLED);
            tx.setCancelledAt(now);
        }
        transactionRepository.saveAll(toCancelFuture);
        toCancelFuture.forEach(creditCardService::syncInvoiceItemForTransaction);

        rule = recurrenceRepository.save(rule);
        return toRuleResponse(rule);
    }

    @Override
    @Transactional
    public RecurrenceRuleResponse resumeRecurrence(UUID ruleId, UUID userId) {
        return toRuleResponse(resume(findOwnedRule(ruleId, userId)));
    }

    @Override
    @Transactional
    public int resumeDueRecurrences() {
        List<RecurrenceRule> due = recurrenceRepository
                .findAllByStatusAndResumeAtLessThanEqualAndDeletedAtIsNull(RecurrenceStatus.PAUSED, Instant.now());
        due.forEach(this::resume);
        return due.size();
    }

    private RecurrenceRule resume(RecurrenceRule rule) {
        if (rule.getStatus() != RecurrenceStatus.PAUSED) {
            throw new BusinessRuleException("Apenas recorrências PAUSADAS podem ser retomadas. Status atual: " + rule.getStatus());
        }

        Instant pausedAt = rule.getPausedAt();
        rule.setStatus(RecurrenceStatus.ACTIVE);
        rule.setPausedAt(null);
        rule.setResumeAt(null);

        // Retoma no calendário da regra (mesmo dia das ocorrências), a partir de hoje. As datas
        // que a pausa cancelou voltam a valer; o período passado da pausa fica cancelado.
        LocalDate today = LocalDate.now();
        LocalDate current = rule.getStartDate();
        while (current.isBefore(today)) {
            current = generatorService.nextOccurrence(rule.getStartDate(), current, rule.getFrequency());
        }

        Map<LocalDate, Transaction> openByDate = new HashMap<>();
        Map<LocalDate, Transaction> cancelledByPause = new HashMap<>();
        for (Transaction tx : transactionRepository.findAllByRecurrenceRule_Id(rule.getId())) {
            if (tx.getCompetenceDate().isBefore(today)) continue;
            if (tx.getStatus() != TransactionStatus.CANCELLED) {
                openByDate.put(tx.getCompetenceDate(), tx);
            } else if (pausedAt != null && tx.getCancelledAt() != null && !tx.getCancelledAt().isBefore(pausedAt)) {
                cancelledByPause.put(tx.getCompetenceDate(), tx);
            }
        }

        List<Transaction> reactivated = new ArrayList<>();
        List<Transaction> created = new ArrayList<>();
        int generated = 0;
        while (generated < LOOKAHEAD_PERIODS) {
            if (rule.getEndDate() != null && current.isAfter(rule.getEndDate())) break;
            if (!openByDate.containsKey(current)) {
                Transaction cancelled = cancelledByPause.get(current);
                if (cancelled != null) {
                    applyRule(cancelled, rule);
                    cancelled.setStatus(TransactionStatus.PENDING);
                    cancelled.setCancelledAt(null);
                    cancelled.setPaymentDate(null);
                    reactivated.add(cancelled);
                } else {
                    created.add(buildTransaction(rule, current));
                }
            }
            current = generatorService.nextOccurrence(rule.getStartDate(), current, rule.getFrequency());
            generated++;
        }

        transactionRepository.saveAll(reactivated).forEach(creditCardService::syncInvoiceItemForTransaction);
        transactionRepository.saveAll(created).forEach(creditCardService::createInvoiceItemForTransaction);

        rule.setNextOccurrenceDate(current);
        if (rule.getEndDate() != null && current.isAfter(rule.getEndDate())) {
            rule.setStatus(RecurrenceStatus.ENDED);
            rule.setNextOccurrenceDate(null);
        }

        return recurrenceRepository.save(rule);
    }

    @Override
    @Transactional
    public DeleteRecurrenceResult deleteRecurrence(UUID ruleId, DeleteRecurrenceStrategy strategy, UUID userId) {
        RecurrenceRule rule = findOwnedRule(ruleId, userId);

        List<Transaction> cancellable = transactionRepository.findAllByRecurrenceRule_IdAndStatusIn(
                ruleId, CANCELLABLE_STATUSES);

        LocalDate today = LocalDate.now();
        List<Transaction> toCancel = switch (strategy) {
            case FUTURE_ONLY -> cancellable.stream()
                    .filter(tx -> !tx.getCompetenceDate().isBefore(today))
                    .collect(Collectors.toList());
            case ALL -> cancellable;
        };

        Instant now = Instant.now();
        for (Transaction tx : toCancel) {
            tx.setStatus(TransactionStatus.CANCELLED);
            tx.setCancelledAt(now);
        }
        transactionRepository.saveAll(toCancel);
        toCancel.forEach(creditCardService::syncInvoiceItemForTransaction);

        rule.setDeletedAt(now);
        rule.setStatus(RecurrenceStatus.DELETED);
        rule.setNextOccurrenceDate(null);
        recurrenceRepository.save(rule);

        return new DeleteRecurrenceResult(toCancel.size());
    }

    @Override
    @Transactional(readOnly = true)
    public List<RecurrenceRuleResponse> listRecurrences(UUID userId) {
        List<RecurrenceRule> rules = recurrenceRepository.findAllByUserIdAndDeletedAtIsNull(userId);
        Map<UUID, LocalDate> nextDue = loadNextDueDates(rules.stream().map(RecurrenceRule::getId).toList());
        return rules.stream()
                .map(rule -> toRuleResponse(rule, nextDue.get(rule.getId())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RecurrenceRuleResponse getRecurrence(UUID ruleId, UUID userId) {
        RecurrenceRule rule = findOwnedRule(ruleId, userId);
        return toRuleResponse(rule);
    }

    @Override
    @Transactional
    public int generatePendingInstances(int lookaheadDays) {
        LocalDate today = LocalDate.now();
        LocalDate cutoff = today.plusDays(lookaheadDays);
        List<RecurrenceRule> dueRules = recurrenceRepository.findActiveRulesDueBy(cutoff);

        int totalGenerated = 0;
        for (RecurrenceRule rule : dueRules) {
            LocalDate nextDate = rule.getNextOccurrenceDate() != null
                    ? rule.getNextOccurrenceDate()
                    : today;

            if (rule.getEndDate() != null && nextDate.isAfter(rule.getEndDate())) {
                rule.setStatus(RecurrenceStatus.ENDED);
                rule.setNextOccurrenceDate(null);
                recurrenceRepository.save(rule);
                continue;
            }

            List<Transaction> newInstances = new ArrayList<>();
            int generated = 0;
            while (generated < LOOKAHEAD_PERIODS) {
                if (rule.getEndDate() != null && nextDate.isAfter(rule.getEndDate())) {
                    break;
                }
                newInstances.add(buildTransaction(rule, nextDate));
                nextDate = generatorService.nextOccurrence(rule.getStartDate(), nextDate, rule.getFrequency());
                generated++;
            }

            if (!newInstances.isEmpty()) {
                transactionRepository.saveAll(newInstances).forEach(creditCardService::createInvoiceItemForTransaction);
                totalGenerated += newInstances.size();
            }

            rule.setNextOccurrenceDate(nextDate);
            if (rule.getEndDate() != null && nextDate.isAfter(rule.getEndDate())) {
                rule.setStatus(RecurrenceStatus.ENDED);
                rule.setNextOccurrenceDate(null);
            }
            recurrenceRepository.save(rule);
        }
        return totalGenerated;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private RecurrenceRule findOwnedRule(UUID ruleId, UUID userId) {
        return recurrenceRepository.findByIdAndUserIdAndDeletedAtIsNull(ruleId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Recurrence rule not found: " + ruleId));
    }

    /**
     * Toda ocorrência nasce PENDENTE, inclusive as de data passada: nada sai da conta sem o
     * usuário confirmar o pagamento (no cartão, quem quita é o pagamento da fatura).
     */
    private Transaction buildTransaction(RecurrenceRule rule, LocalDate date) {
        Transaction tx = new Transaction();
        tx.setUserId(rule.getUserId());
        tx.setType(rule.getType());
        tx.setStatus(TransactionStatus.PENDING);
        tx.setCompetenceDate(date);
        tx.setRecurrenceRule(rule);
        applyRule(tx, rule);
        return tx;
    }

    /** Campos que a ocorrência herda da regra. */
    private void applyRule(Transaction tx, RecurrenceRule rule) {
        tx.setAccount(rule.getAccount());
        tx.setAmount(rule.getAmount());
        tx.setDescription(rule.getDescription());
        tx.setCategory(rule.getCategory());
        tx.setSubcategory(rule.getSubcategory());
        tx.setPaymentMethod(rule.getPaymentMethod());
        tx.setCreditCard(rule.getCreditCard());
    }

    private Map<UUID, LocalDate> loadNextDueDates(Collection<UUID> ruleIds) {
        Map<UUID, LocalDate> byId = new HashMap<>();
        if (ruleIds.isEmpty()) {
            return byId;
        }
        for (Object[] row : transactionRepository.findNextOpenOccurrenceByRule(ruleIds, LocalDate.now())) {
            byId.put((UUID) row[0], (LocalDate) row[1]);
        }
        return byId;
    }

    private RecurrenceRuleResponse toRuleResponse(RecurrenceRule rule) {
        return toRuleResponse(rule, loadNextDueDates(List.of(rule.getId())).get(rule.getId()));
    }

    private RecurrenceRuleResponse toRuleResponse(RecurrenceRule rule, LocalDate nextDueDate) {
        return new RecurrenceRuleResponse(
                rule.getId(),
                rule.getAccount() != null ? rule.getAccount().getId() : null,
                rule.getAccount() != null ? rule.getAccount().getName() : null,
                rule.getType(),
                rule.getFrequency(),
                rule.getStatus(),
                rule.getAmount(),
                rule.getDescription(),
                rule.getCategory() != null ? rule.getCategory().getId() : null,
                rule.getCategory() != null ? rule.getCategory().getName() : null,
                rule.getSubcategory() != null ? rule.getSubcategory().getId() : null,
                rule.getSubcategory() != null ? rule.getSubcategory().getName() : null,
                rule.getStartDate(),
                rule.getEndDate(),
                rule.getNextOccurrenceDate(),
                nextDueDate,
                rule.getPaymentMethod() != null ? rule.getPaymentMethod().getSlug() : null,
                rule.getCreditCard() != null ? rule.getCreditCard().getId() : null,
                rule.getCreditCard() != null ? rule.getCreditCard().getName() : null,
                rule.getPausedAt(),
                rule.getResumeAt(),
                rule.getCreatedAt(),
                rule.getUpdatedAt()
        );
    }

}
