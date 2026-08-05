package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.domain.entity.CategoryRule;
import com.cashcontrol.api.domain.entity.CreditCard;
import com.cashcontrol.api.domain.entity.PaymentMethod;
import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
import com.cashcontrol.api.domain.entity.Tag;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ForbiddenAccessException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.request.CreateTransactionRequest;
import com.cashcontrol.api.dto.request.EditTransactionRequest;
import com.cashcontrol.api.dto.request.MarkAsPaidRequest;
import com.cashcontrol.api.dto.request.TransactionFilterRequest;
import com.cashcontrol.api.dto.response.CreditCardRefResponse;
import com.cashcontrol.api.dto.response.PaymentMethodResponse;
import com.cashcontrol.api.dto.response.TagResponse;
import com.cashcontrol.api.dto.response.TransactionDetailResponse;
import com.cashcontrol.api.dto.response.TransactionSummaryResponse;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.CategoryRepository;
import com.cashcontrol.api.repository.CategoryRuleRepository;
import com.cashcontrol.api.repository.CreditCardRepository;
import com.cashcontrol.api.repository.PaymentMethodRepository;
import com.cashcontrol.api.repository.TagRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private final PaymentMethodRepository paymentMethodRepository;
    private final CreditCardRepository creditCardRepository;
    private final CreditCardService creditCardService;

    @Override
    @Transactional
    public TransactionDetailResponse createTransaction(CreateTransactionRequest request, UUID userId) {
        if (request.type() == TransactionType.TRANSFER) {
            throw new BusinessRuleException("Use o endpoint dedicado de transferência para criar transações do tipo TRANSFER.");
        }
        if (request.type() == TransactionType.MANUAL_ADJUSTMENT) {
            throw new BusinessRuleException("Use o endpoint de ajuste manual para criar transações do tipo MANUAL_ADJUSTMENT.");
        }

        Account account = accountRepository.findByIdAndUserIdAndDeletedAtIsNull(request.accountId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + request.accountId()));

        if (account.getArchivedAt() != null) {
            throw new BusinessRuleException("Não é possível criar uma transação em uma conta arquivada.");
        }

        PaymentMethod paymentMethod = resolvePaymentMethod(request.paymentMethod());
        CreditCard creditCard = validateAndResolveCreditCard(
                request.creditCardId(), paymentMethod.getSlug(), userId);

        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setAccount(account);
        tx.setType(request.type());
        tx.setAmount(request.amount());
        tx.setDescription(request.description());
        tx.setNotes(request.notes());
        tx.setCompetenceDate(request.competenceDate());
        tx.setLocation(request.location());
        tx.setPaymentMethod(paymentMethod);
        tx.setCreditCard(creditCard);

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
        creditCardService.createInvoiceItemForTransaction(tx);
        return toDetail(tx);
    }

    @Override
    @Transactional
    public TransactionDetailResponse editTransaction(UUID id, EditTransactionRequest request, UUID userId) {
        Transaction tx = findOwnedTransaction(id, userId);

        if (tx.getStatus() == TransactionStatus.CANCELLED) {
            throw new BusinessRuleException("Transações canceladas não podem ser editadas.");
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
        // Reenviar o status atual é o comportamento natural de um PUT com o recurso
        // completo (o formulário de edição devolve todos os campos): X → X é no-op.
        if (request.status() != null && request.status() != tx.getStatus()) {
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

        if (request.paymentMethod() != null || request.creditCardId() != null) {
            PaymentMethodSlug targetSlug = request.paymentMethod() != null
                    ? request.paymentMethod()
                    : tx.getPaymentMethod().getSlug();
            PaymentMethod paymentMethod = resolvePaymentMethod(request.paymentMethod() != null
                    ? request.paymentMethod() : tx.getPaymentMethod().getSlug());
            UUID targetCreditCardId = request.creditCardId() != null
                    ? request.creditCardId()
                    : (targetSlug == PaymentMethodSlug.CREDIT_CARD
                            ? (tx.getCreditCard() != null ? tx.getCreditCard().getId() : null)
                            : null);
            CreditCard creditCard = validateAndResolveCreditCard(targetCreditCardId, paymentMethod.getSlug(), userId);
            tx.setPaymentMethod(paymentMethod);
            tx.setCreditCard(creditCard);
        }

        applyCategory(tx, request.categoryId(), request.subcategoryId(), userId, null);
        if (request.tagIds() != null) {
            applyTags(tx, request.tagIds(), userId);
        }

        tx = transactionRepository.save(tx);
        creditCardService.syncInvoiceItemForTransaction(tx);
        return toDetail(tx);
    }

    @Override
    @Transactional
    public void deleteTransaction(UUID id, UUID userId) {
        Transaction tx = findOwnedTransaction(id, userId);

        if (tx.getTransferGroupId() != null) {
            throw new BusinessRuleException(
                    "As pernas de uma transferência devem ser excluídas em par via DELETE /api/v1/accounts/transfers/{groupId}.");
        }

        // A single installment is a slice of a contract with the issuer, not a standalone
        // fact: dropping one would leave a gap in the series and desynchronise it from the
        // real invoice. The series-level operations are the supported way out.
        if (tx.getInstallmentSeries() != null) {
            throw new BusinessRuleException(
                    "Parcelas não podem ser excluídas individualmente. Use POST /api/v1/installments/series/{seriesId}/settle "
                    + "para cancelar as parcelas restantes, ou DELETE /api/v1/installments/series/{seriesId} "
                    + "para remover o parcelamento inteiro.");
        }

        creditCardService.detachInvoiceItemForTransaction(tx.getId());
        transactionRepository.delete(tx);
    }

    @Override
    @Transactional
    public TransactionDetailResponse markAsPaid(UUID id, MarkAsPaidRequest request, UUID userId) {
        Transaction tx = findOwnedTransaction(id, userId);

        if (tx.getStatus() != TransactionStatus.PENDING && tx.getStatus() != TransactionStatus.OVERDUE) {
            throw new BusinessRuleException(
                    "Apenas transações PENDENTES ou VENCIDAS podem ser marcadas como pagas. Status atual: " + tx.getStatus());
        }

        tx.setStatus(TransactionStatus.PAID);
        tx.setPaymentDate(request.paymentDate() != null ? request.paymentDate() : LocalDate.now());

        tx = transactionRepository.save(tx);
        creditCardService.syncInvoiceItemForTransaction(tx);
        return toDetail(tx);
    }

    @Override
    @Transactional
    public TransactionDetailResponse cancelTransaction(UUID id, UUID userId) {
        Transaction tx = findOwnedTransaction(id, userId);

        if (tx.getStatus() == TransactionStatus.CANCELLED) {
            throw new BusinessRuleException("A transação já está cancelada.");
        }

        tx.setStatus(TransactionStatus.CANCELLED);
        tx.setCancelledAt(Instant.now());

        tx = transactionRepository.save(tx);
        creditCardService.syncInvoiceItemForTransaction(tx);
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
                filter.paymentMethod(),
                filter.groupInstallments(),
                pageable
        );

        if (!filter.groupInstallments()) {
            return page.map(this::toSummary);
        }

        Set<UUID> seriesIds = page.getContent().stream()
                .filter(tx -> tx.getInstallmentSeries() != null && tx.getInstallmentNumber() != null)
                .map(tx -> tx.getInstallmentSeries().getId())
                .collect(Collectors.toSet());

        if (seriesIds.isEmpty()) {
            return page.map(this::toSummary);
        }

        Map<UUID, SeriesAggregate> aggregates = loadSeriesAggregates(seriesIds);
        return page.map(tx -> {
            if (tx.getInstallmentSeries() == null || tx.getInstallmentNumber() == null) {
                return toSummary(tx);
            }
            SeriesAggregate agg = aggregates.get(tx.getInstallmentSeries().getId());
            return agg != null ? toGroupedSummary(tx, agg) : toSummary(tx);
        });
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Override
    public PaymentMethod resolvePaymentMethod(PaymentMethodSlug slug) {
        PaymentMethodSlug target = slug != null ? slug : PaymentMethodSlug.OTHER;
        return paymentMethodRepository.findBySlug(target)
                .orElseThrow(() -> new ResourceNotFoundException("Payment method not found: " + target));
    }

    @Override
    public CreditCard validateAndResolveCreditCard(UUID creditCardId, PaymentMethodSlug slug, UUID userId) {
        if (slug == PaymentMethodSlug.CREDIT_CARD) {
            if (creditCardId == null) {
                throw new BusinessRuleException("creditCardId é obrigatório quando paymentMethod é CREDIT_CARD.");
            }
            CreditCard card = creditCardRepository.findByIdAndUserIdAndDeletedAtIsNull(creditCardId, userId)
                    .orElseThrow(() -> new ForbiddenAccessException(
                            "Cartão de crédito não encontrado ou não pertence ao usuário atual."));
            if (card.getArchivedAt() != null) {
                throw new BusinessRuleException("Não é possível usar um cartão de crédito arquivado nesta transação.");
            }
            return card;
        }
        if (creditCardId != null) {
            throw new BusinessRuleException(
                    "creditCardId não deve ser informado quando paymentMethod não é CREDIT_CARD.");
        }
        return null;
    }

    private Transaction findOwnedTransaction(UUID id, UUID userId) {
        return transactionRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + id));
    }

    private void validateStatusTransition(TransactionStatus from, TransactionStatus to) {
        if (from == to) {
            return;
        }
        boolean valid = switch (from) {
            case PENDING -> to == TransactionStatus.PAID || to == TransactionStatus.OVERDUE || to == TransactionStatus.CANCELLED;
            case OVERDUE -> to == TransactionStatus.PAID || to == TransactionStatus.CANCELLED;
            case PAID -> false;
            case CANCELLED -> false;
        };
        if (!valid) {
            throw new BusinessRuleException(
                    "Transição de status inválida: " + from + " → " + to);
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

    @Override
    public TransactionSummaryResponse toSummary(Transaction tx) {
        return TransactionSummaryResponse.ungrouped(
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
                tx.getCreatedAt(),
                summaryPaymentMethod(tx),
                tx.getInstallmentSeries() != null ? tx.getInstallmentSeries().getId() : null,
                tx.getInstallmentNumber(),
                tx.getTotalInstallments()
        );
    }

    /**
     * Linha que representa o parcelamento inteiro: valor total da compra e status derivado
     * do conjunto das parcelas. A data de competência é a da parcela representante — a
     * primeira do recorte filtrado —, para que a linha não escape da janela consultada.
     */
    private TransactionSummaryResponse toGroupedSummary(Transaction tx, SeriesAggregate agg) {
        TransactionStatus status;
        if (agg.activeCount() == 0) {
            status = TransactionStatus.CANCELLED;
        } else if (agg.paidCount() == agg.activeCount()) {
            status = TransactionStatus.PAID;
        } else if (agg.overdueCount() > 0) {
            status = TransactionStatus.OVERDUE;
        } else {
            status = TransactionStatus.PENDING;
        }

        return new TransactionSummaryResponse(
                tx.getId(),
                tx.getAccount().getId(),
                tx.getAccount().getName(),
                tx.getType(),
                status,
                agg.totalAmount(),
                tx.getDescription(),
                tx.getCompetenceDate(),
                status == TransactionStatus.PAID ? agg.lastPaymentDate() : null,
                tx.getCategory() != null ? tx.getCategory().getId() : null,
                tx.getCategory() != null ? tx.getCategory().getName() : null,
                tx.getCreatedAt(),
                summaryPaymentMethod(tx),
                tx.getInstallmentSeries().getId(),
                tx.getInstallmentNumber(),
                tx.getTotalInstallments(),
                agg.totalAmount(),
                agg.paidCount(),
                true
        );
    }

    private PaymentMethodResponse summaryPaymentMethod(Transaction tx) {
        return tx.getPaymentMethod() != null
                ? new PaymentMethodResponse(
                        tx.getPaymentMethod().getId(),
                        tx.getPaymentMethod().getSlug(),
                        tx.getPaymentMethod().getName())
                : null;
    }

    private record SeriesAggregate(
            BigDecimal totalAmount, int paidCount, int activeCount, int overdueCount, LocalDate lastPaymentDate) {}

    private Map<UUID, SeriesAggregate> loadSeriesAggregates(Set<UUID> seriesIds) {
        Map<UUID, SeriesAggregate> byId = new HashMap<>();
        for (Object[] row : transactionRepository.aggregateInstallmentSeries(seriesIds)) {
            byId.put((UUID) row[0], new SeriesAggregate(
                    (BigDecimal) row[1],
                    ((Number) row[2]).intValue(),
                    ((Number) row[3]).intValue(),
                    ((Number) row[4]).intValue(),
                    (LocalDate) row[5]));
        }
        return byId;
    }

    @Override
    public TransactionDetailResponse toDetail(Transaction tx) {
        Set<TagResponse> tagResponses = tx.getTags().stream()
                .map(t -> new TagResponse(t.getId(), t.getName(), t.getColor()))
                .collect(Collectors.toSet());

        PaymentMethodResponse pmResponse = tx.getPaymentMethod() != null
                ? new PaymentMethodResponse(
                        tx.getPaymentMethod().getId(),
                        tx.getPaymentMethod().getSlug(),
                        tx.getPaymentMethod().getName())
                : null;

        CreditCardRefResponse cardResponse = tx.getCreditCard() != null
                ? new CreditCardRefResponse(
                        tx.getCreditCard().getId(),
                        tx.getCreditCard().getName(),
                        tx.getCreditCard().getBrand())
                : null;

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
                tx.getUpdatedAt(),
                pmResponse,
                cardResponse
        );
    }
}
