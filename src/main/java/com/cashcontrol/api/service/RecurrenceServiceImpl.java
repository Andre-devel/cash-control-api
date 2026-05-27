package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.domain.entity.DeleteRecurrenceStrategy;
import com.cashcontrol.api.domain.entity.RecurrenceRule;
import com.cashcontrol.api.domain.entity.RecurrenceStatus;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.request.CreateRecurrenceRequest;
import com.cashcontrol.api.dto.request.EditRecurrenceRequest;
import com.cashcontrol.api.dto.request.PauseRecurrenceRequest;
import com.cashcontrol.api.dto.response.DeleteRecurrenceResult;
import com.cashcontrol.api.dto.response.EditRecurrenceResult;
import com.cashcontrol.api.dto.response.RecurrenceCreationResponse;
import com.cashcontrol.api.dto.response.RecurrenceRuleResponse;
import com.cashcontrol.api.dto.response.TagResponse;
import com.cashcontrol.api.dto.response.TransactionDetailResponse;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.CategoryRepository;
import com.cashcontrol.api.repository.RecurrenceRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
    private final CategoryRepository categoryRepository;
    private final RecurrenceGeneratorService generatorService;

    @Override
    @Transactional
    public RecurrenceCreationResponse createRecurrence(CreateRecurrenceRequest request, UUID userId) {
        Account account = accountRepository.findByIdAndUserIdAndDeletedAtIsNull(request.accountId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + request.accountId()));

        if (account.getArchivedAt() != null) {
            throw new BusinessRuleException("Cannot create a recurrence on an archived account.");
        }

        Category category = resolveCategory(request.categoryId());
        Category subcategory = resolveSubcategory(request.subcategoryId(), category);

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

        LocalDate today = LocalDate.now();
        LocalDate firstDate = request.startDate();
        rule.setNextOccurrenceDate(firstDate);

        rule = recurrenceRepository.save(rule);

        // Create first instance immediately
        Transaction firstInstance = buildTransaction(rule, firstDate, today);
        firstInstance = transactionRepository.save(firstInstance);

        // Pre-generate up to LOOKAHEAD_PERIODS future instances
        LocalDate nextDate = generatorService.nextOccurrence(firstDate, request.frequency());
        List<Transaction> upcoming = new ArrayList<>();
        int generated = 1;

        while (generated < LOOKAHEAD_PERIODS) {
            if (rule.getEndDate() != null && nextDate.isAfter(rule.getEndDate())) {
                break;
            }
            upcoming.add(buildTransaction(rule, nextDate, today));
            nextDate = generatorService.nextOccurrence(nextDate, request.frequency());
            generated++;
        }

        if (!upcoming.isEmpty()) {
            transactionRepository.saveAll(upcoming);
        }

        // Advance nextOccurrenceDate to after last pre-generated instance
        rule.setNextOccurrenceDate(nextDate);
        if (rule.getEndDate() != null && nextDate.isAfter(rule.getEndDate())) {
            rule.setStatus(RecurrenceStatus.ENDED);
            rule.setNextOccurrenceDate(null);
        }
        rule = recurrenceRepository.save(rule);

        return new RecurrenceCreationResponse(toRuleResponse(rule), toDetail(firstInstance));
    }

    @Override
    @Transactional
    public EditRecurrenceResult editSeries(UUID ruleId, EditRecurrenceRequest request, UUID userId) {
        RecurrenceRule rule = findOwnedRule(ruleId, userId);

        if (rule.getStatus() == RecurrenceStatus.DELETED) {
            throw new BusinessRuleException("Cannot edit a deleted recurrence rule.");
        }

        Account account = null;
        if (request.accountId() != null) {
            account = accountRepository.findByIdAndUserIdAndDeletedAtIsNull(request.accountId(), userId)
                    .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + request.accountId()));
            if (account.getArchivedAt() != null) {
                throw new BusinessRuleException("Cannot move recurrence to an archived account.");
            }
        }

        Category category = request.categoryId() != null ? resolveCategory(request.categoryId()) : null;
        Category subcategory = request.subcategoryId() != null
                ? resolveSubcategory(request.subcategoryId(), category)
                : null;

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

        return new EditRecurrenceResult(toRuleResponse(rule), updated.size());
    }

    @Override
    @Transactional
    public RecurrenceRuleResponse pauseRecurrence(UUID ruleId, PauseRecurrenceRequest request, UUID userId) {
        RecurrenceRule rule = findOwnedRule(ruleId, userId);

        if (rule.getStatus() != RecurrenceStatus.ACTIVE) {
            throw new BusinessRuleException("Only ACTIVE recurrences can be paused. Current status: " + rule.getStatus());
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

        rule = recurrenceRepository.save(rule);
        return toRuleResponse(rule);
    }

    @Override
    @Transactional
    public RecurrenceRuleResponse resumeRecurrence(UUID ruleId, UUID userId) {
        RecurrenceRule rule = findOwnedRule(ruleId, userId);

        if (rule.getStatus() != RecurrenceStatus.PAUSED) {
            throw new BusinessRuleException("Only PAUSED recurrences can be resumed. Current status: " + rule.getStatus());
        }

        rule.setStatus(RecurrenceStatus.ACTIVE);
        rule.setPausedAt(null);
        rule.setResumeAt(null);

        // Re-generate instances from today forward
        LocalDate today = LocalDate.now();
        LocalDate nextDate = (rule.getNextOccurrenceDate() != null && !rule.getNextOccurrenceDate().isBefore(today))
                ? rule.getNextOccurrenceDate()
                : today;

        // Check end date
        if (rule.getEndDate() != null && nextDate.isAfter(rule.getEndDate())) {
            rule.setStatus(RecurrenceStatus.ENDED);
            rule.setNextOccurrenceDate(null);
            rule = recurrenceRepository.save(rule);
            return toRuleResponse(rule);
        }

        List<Transaction> newInstances = new ArrayList<>();
        int generated = 0;
        LocalDate current = nextDate;

        while (generated < LOOKAHEAD_PERIODS) {
            if (rule.getEndDate() != null && current.isAfter(rule.getEndDate())) break;
            newInstances.add(buildTransaction(rule, current, today));
            current = generatorService.nextOccurrence(current, rule.getFrequency());
            generated++;
        }

        if (!newInstances.isEmpty()) {
            transactionRepository.saveAll(newInstances);
        }

        rule.setNextOccurrenceDate(current);
        if (rule.getEndDate() != null && current.isAfter(rule.getEndDate())) {
            rule.setStatus(RecurrenceStatus.ENDED);
            rule.setNextOccurrenceDate(null);
        }

        rule = recurrenceRepository.save(rule);
        return toRuleResponse(rule);
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

        rule.setDeletedAt(now);
        rule.setStatus(RecurrenceStatus.DELETED);
        rule.setNextOccurrenceDate(null);
        recurrenceRepository.save(rule);

        return new DeleteRecurrenceResult(toCancel.size());
    }

    @Override
    @Transactional(readOnly = true)
    public List<RecurrenceRuleResponse> listRecurrences(UUID userId) {
        return recurrenceRepository.findAllByUserIdAndDeletedAtIsNull(userId)
                .stream()
                .map(this::toRuleResponse)
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
                newInstances.add(buildTransaction(rule, nextDate, today));
                nextDate = generatorService.nextOccurrence(nextDate, rule.getFrequency());
                generated++;
            }

            if (!newInstances.isEmpty()) {
                transactionRepository.saveAll(newInstances);
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

    private Transaction buildTransaction(RecurrenceRule rule, LocalDate date, LocalDate today) {
        TransactionStatus status = date.isAfter(today) ? TransactionStatus.PENDING : TransactionStatus.PAID;

        Transaction tx = new Transaction();
        tx.setUserId(rule.getUserId());
        tx.setAccount(rule.getAccount());
        tx.setType(rule.getType());
        tx.setStatus(status);
        tx.setAmount(rule.getAmount());
        tx.setDescription(rule.getDescription());
        tx.setCompetenceDate(date);
        tx.setPaymentDate(status == TransactionStatus.PAID ? date : null);
        tx.setRecurrenceRule(rule);
        tx.setCategory(rule.getCategory());
        tx.setSubcategory(rule.getSubcategory());
        return tx;
    }

    private Category resolveCategory(UUID categoryId) {
        if (categoryId == null) return null;
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + categoryId));
    }

    private Category resolveSubcategory(UUID subcategoryId, Category parent) {
        if (subcategoryId == null) return null;
        return categoryRepository.findById(subcategoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Subcategory not found: " + subcategoryId));
    }

    private RecurrenceRuleResponse toRuleResponse(RecurrenceRule rule) {
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
                rule.getPausedAt(),
                rule.getResumeAt(),
                rule.getCreatedAt(),
                rule.getUpdatedAt()
        );
    }

    private TransactionDetailResponse toDetail(Transaction tx) {
        Set<TagResponse> tagResponses = tx.getTags().stream()
                .map(t -> new TagResponse(t.getId(), t.getName(), t.getColor()))
                .collect(Collectors.toSet());

        return new TransactionDetailResponse(
                tx.getId(),
                tx.getAccount().getId(),
                tx.getAccount().getName(),
                tx.getType(),
                tx.getStatus(),
                tx.getAmount(),
                tx.getDescription(),
                tx.getNotes(),
                tx.getCompetenceDate(),
                tx.getPaymentDate(),
                tx.getCategory() != null ? tx.getCategory().getId() : null,
                tx.getCategory() != null ? tx.getCategory().getName() : null,
                tx.getSubcategory() != null ? tx.getSubcategory().getId() : null,
                tx.getSubcategory() != null ? tx.getSubcategory().getName() : null,
                tagResponses,
                tx.getLocation(),
                tx.getTransferGroupId(),
                tx.getInstallmentSeries() != null ? tx.getInstallmentSeries().getId() : null,
                tx.getInstallmentNumber(),
                tx.getTotalInstallments(),
                tx.isDetached(),
                tx.getCancelledAt(),
                tx.getCreatedAt(),
                tx.getUpdatedAt()
        );
    }
}
