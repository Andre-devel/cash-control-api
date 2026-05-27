package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.domain.entity.CategoryRule;
import com.cashcontrol.api.domain.entity.Tag;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.request.CreateTransactionRequest;
import com.cashcontrol.api.dto.request.EditTransactionRequest;
import com.cashcontrol.api.dto.request.MarkAsPaidRequest;
import com.cashcontrol.api.dto.request.TransactionFilterRequest;
import com.cashcontrol.api.dto.response.TagResponse;
import com.cashcontrol.api.dto.response.TransactionDetailResponse;
import com.cashcontrol.api.dto.response.TransactionSummaryResponse;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.CategoryRepository;
import com.cashcontrol.api.repository.CategoryRuleRepository;
import com.cashcontrol.api.repository.TagRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TransactionServiceImpl implements TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final CategoryRuleRepository categoryRuleRepository;
    private final TagRepository tagRepository;

    @Override
    @Transactional
    public TransactionDetailResponse createTransaction(CreateTransactionRequest request, UUID userId) {
        if (request.type() == TransactionType.TRANSFER) {
            throw new BusinessRuleException("Use the dedicated transfer endpoint to create TRANSFER transactions.");
        }
        if (request.type() == TransactionType.MANUAL_ADJUSTMENT) {
            throw new BusinessRuleException("Use the manual adjustment endpoint to create MANUAL_ADJUSTMENT transactions.");
        }

        Account account = accountRepository.findByIdAndUserIdAndDeletedAtIsNull(request.accountId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + request.accountId()));

        if (account.getArchivedAt() != null) {
            throw new BusinessRuleException("Cannot create a transaction on an archived account.");
        }

        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setAccount(account);
        tx.setType(request.type());
        tx.setAmount(request.amount());
        tx.setDescription(request.description());
        tx.setNotes(request.notes());
        tx.setCompetenceDate(request.competenceDate());
        tx.setLocation(request.location());

        TransactionStatus status = request.status() != null ? request.status() : TransactionStatus.PAID;
        tx.setStatus(status);

        if (status == TransactionStatus.PAID) {
            tx.setPaymentDate(request.paymentDate() != null ? request.paymentDate() : request.competenceDate());
        } else {
            tx.setPaymentDate(request.paymentDate());
        }

        applyCategory(tx, request.categoryId(), request.subcategoryId(), userId, request.description());
        applyTags(tx, request.tagIds(), userId);

        tx = transactionRepository.save(tx);
        return toDetail(tx);
    }

    @Override
    @Transactional
    public TransactionDetailResponse editTransaction(UUID id, EditTransactionRequest request, UUID userId) {
        Transaction tx = findOwnedTransaction(id, userId);

        if (tx.getStatus() == TransactionStatus.CANCELLED) {
            throw new BusinessRuleException("Cancelled transactions cannot be edited.");
        }

        if (tx.getInstallmentSeries() != null && !tx.isDetached()) {
            tx.setDetached(true);
        }

        if (request.amount() != null) {
            tx.setAmount(request.amount());
        }
        if (request.description() != null) {
            tx.setDescription(request.description());
        }
        if (request.notes() != null) {
            tx.setNotes(request.notes());
        }
        if (request.competenceDate() != null) {
            tx.setCompetenceDate(request.competenceDate());
        }
        if (request.paymentDate() != null) {
            tx.setPaymentDate(request.paymentDate());
        }
        if (request.status() != null) {
            validateStatusTransition(tx.getStatus(), request.status());
            tx.setStatus(request.status());
            if (request.status() == TransactionStatus.PAID && tx.getPaymentDate() == null) {
                tx.setPaymentDate(LocalDate.now());
            }
            if (request.status() == TransactionStatus.CANCELLED) {
                tx.setCancelledAt(Instant.now());
            }
        }
        if (request.location() != null) {
            tx.setLocation(request.location());
        }

        applyCategory(tx, request.categoryId(), request.subcategoryId(), userId, null);
        if (request.tagIds() != null) {
            applyTags(tx, request.tagIds(), userId);
        }

        tx = transactionRepository.save(tx);
        return toDetail(tx);
    }

    @Override
    @Transactional
    public void deleteTransaction(UUID id, UUID userId) {
        Transaction tx = findOwnedTransaction(id, userId);

        if (tx.getTransferGroupId() != null) {
            throw new BusinessRuleException(
                    "Transfer legs must be deleted as a pair via DELETE /api/v1/accounts/transfers/{groupId}.");
        }

        transactionRepository.delete(tx);
    }

    @Override
    @Transactional
    public TransactionDetailResponse markAsPaid(UUID id, MarkAsPaidRequest request, UUID userId) {
        Transaction tx = findOwnedTransaction(id, userId);

        if (tx.getStatus() != TransactionStatus.PENDING && tx.getStatus() != TransactionStatus.OVERDUE) {
            throw new BusinessRuleException(
                    "Only PENDING or OVERDUE transactions can be marked as paid. Current status: " + tx.getStatus());
        }

        tx.setStatus(TransactionStatus.PAID);
        tx.setPaymentDate(request.paymentDate() != null ? request.paymentDate() : LocalDate.now());

        tx = transactionRepository.save(tx);
        return toDetail(tx);
    }

    @Override
    @Transactional
    public TransactionDetailResponse cancelTransaction(UUID id, UUID userId) {
        Transaction tx = findOwnedTransaction(id, userId);

        if (tx.getStatus() == TransactionStatus.CANCELLED) {
            throw new BusinessRuleException("Transaction is already cancelled.");
        }

        tx.setStatus(TransactionStatus.CANCELLED);
        tx.setCancelledAt(Instant.now());

        tx = transactionRepository.save(tx);
        return toDetail(tx);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TransactionSummaryResponse> listTransactions(TransactionFilterRequest filter, UUID userId, Pageable pageable) {
        Page<Transaction> page = transactionRepository.findWithFilters(
                userId,
                filter.accountId(),
                filter.type(),
                filter.status(),
                filter.categoryId(),
                filter.competenceDateFrom(),
                filter.competenceDateTo(),
                filter.paymentDateFrom(),
                filter.paymentDateTo(),
                filter.amountMin(),
                filter.amountMax(),
                filter.searchText(),
                filter.includeCancelled(),
                pageable
        );
        return page.map(this::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public TransactionDetailResponse getTransaction(UUID id, UUID userId) {
        Transaction tx = findOwnedTransaction(id, userId);
        return toDetail(tx);
    }

    @Override
    @Transactional
    public int detectOverdue(UUID userId) {
        return transactionRepository.markOverdueForUser(userId, LocalDate.now());
    }

    @Override
    @Transactional
    public int detectOverdueAll() {
        return transactionRepository.markOverdueAll(LocalDate.now());
    }

    private Transaction findOwnedTransaction(UUID id, UUID userId) {
        return transactionRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + id));
    }

    private void validateStatusTransition(TransactionStatus from, TransactionStatus to) {
        boolean valid = switch (from) {
            case PENDING -> to == TransactionStatus.PAID || to == TransactionStatus.OVERDUE || to == TransactionStatus.CANCELLED;
            case OVERDUE -> to == TransactionStatus.PAID || to == TransactionStatus.CANCELLED;
            case PAID -> false;
            case CANCELLED -> false;
        };
        if (!valid) {
            throw new BusinessRuleException(
                    "Invalid status transition: " + from + " → " + to);
        }
    }

    private void applyCategory(Transaction tx, UUID categoryId, UUID subcategoryId, UUID userId, String description) {
        if (categoryId != null) {
            Category category = categoryRepository.findById(categoryId)
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + categoryId));
            tx.setCategory(category);

            if (subcategoryId != null) {
                Category subcategory = categoryRepository.findById(subcategoryId)
                        .orElseThrow(() -> new ResourceNotFoundException("Subcategory not found: " + subcategoryId));
                tx.setSubcategory(subcategory);
            } else {
                tx.setSubcategory(null);
            }
        } else if (categoryId == null && description != null && tx.getCategory() == null) {
            applyCategoryRule(tx, userId, description);
        }
    }

    private void applyCategoryRule(Transaction tx, UUID userId, String description) {
        List<CategoryRule> rules = categoryRuleRepository.findAllByUserIdAndIsActiveTrueOrderByPriorityAsc(userId);
        String lowerDesc = description.toLowerCase();
        for (CategoryRule rule : rules) {
            if (lowerDesc.contains(rule.getPattern().toLowerCase())) {
                tx.setCategory(rule.getCategory());
                if (rule.getSubcategory() != null) {
                    tx.setSubcategory(rule.getSubcategory());
                }
                break;
            }
        }
    }

    private void applyTags(Transaction tx, List<UUID> tagIds, UUID userId) {
        if (tagIds == null || tagIds.isEmpty()) {
            tx.getTags().clear();
            return;
        }
        List<Tag> tags = tagRepository.findAllByUserIdAndIdIn(userId, tagIds);
        tx.getTags().clear();
        tx.getTags().addAll(tags);
    }

    private TransactionSummaryResponse toSummary(Transaction tx) {
        return new TransactionSummaryResponse(
                tx.getId(),
                tx.getAccount().getId(),
                tx.getAccount().getName(),
                tx.getType(),
                tx.getStatus(),
                tx.getAmount(),
                tx.getDescription(),
                tx.getCompetenceDate(),
                tx.getPaymentDate(),
                tx.getCategory() != null ? tx.getCategory().getId() : null,
                tx.getCategory() != null ? tx.getCategory().getName() : null,
                tx.getCreatedAt()
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
