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
import com.cashcontrol.api.dto.request.ReimbursementRequest;
import com.cashcontrol.api.dto.request.TransactionFilterRequest;
import com.cashcontrol.api.dto.response.CreditCardRefResponse;
import com.cashcontrol.api.dto.response.PaymentMethodResponse;
import com.cashcontrol.api.dto.response.ReimbursementResponse;
import com.cashcontrol.api.dto.response.TagResponse;
import com.cashcontrol.api.dto.response.TransactionDetailResponse;
import com.cashcontrol.api.dto.response.TransactionSummaryResponse;
import com.cashcontrol.api.dto.response.TransactionTotalsResponse;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.CategoryRuleRepository;
import com.cashcontrol.api.repository.CreditCardRepository;
import com.cashcontrol.api.repository.PaymentMethodRepository;
import com.cashcontrol.api.repository.TagRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
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
    private final AttachmentService attachmentService;
    private final CategoryResolver categoryResolver;
    private final CategoryRuleRepository categoryRuleRepository;
    private final TagRepository tagRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final CreditCardRepository creditCardRepository;
    private final CreditCardService creditCardService;
    private final CategoryRuleMatcher categoryRuleMatcher;

    @Override
    @Transactional
    public TransactionDetailResponse createTransaction(CreateTransactionRequest request, UUID userId) {
        if (request.type() == TransactionType.TRANSFER) {
            throw new BusinessRuleException("Use o endpoint dedicado de transferência para criar transações do tipo TRANSFER.");
        }
        if (request.type() == TransactionType.MANUAL_ADJUSTMENT) {
            throw new BusinessRuleException("Use o endpoint de ajuste manual para criar transações do tipo MANUAL_ADJUSTMENT.");
        }
        if (request.type() == TransactionType.INVOICE_PAYMENT) {
            throw new BusinessRuleException("Pagamentos de fatura são lançados pelo pagamento da própria fatura.");
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

        TransactionStatus status;
        if (creditCard != null) {
            if (request.status() == TransactionStatus.PAID) {
                throw new BusinessRuleException(CARD_PURCHASE_PAID_BY_INVOICE);
            }
            // Sem data de pagamento: quem a define é o pagamento da fatura (a compra vence
            // pela fatura, e o job de vencidas não olha compras no cartão).
            status = request.status() != null ? request.status() : TransactionStatus.PENDING;
            tx.setPaymentDate(null);
        } else {
            status = request.status() != null ? request.status() : TransactionStatus.PAID;
            tx.setPaymentDate(status == TransactionStatus.PAID && request.paymentDate() == null
                    ? request.competenceDate()
                    : request.paymentDate());
        }
        tx.setStatus(status);

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
        rejectInvoicePayment(tx);
        boolean wasOnCard = tx.getCreditCard() != null;
        TransactionStatus previousStatus = tx.getStatus();

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

        if (request.type() != null && request.type() != tx.getType()) {
            // Receita e reembolso pesam igual no saldo; o estorno no cartão (REFUND com cartão)
            // entra na fatura, então fica de fora.
            if (!isInflow(tx.getType()) || !isInflow(request.type()) || tx.getCreditCard() != null) {
                throw new BusinessRuleException("Só é possível trocar o tipo entre receita e reembolso, fora do cartão.");
            }
            tx.setType(request.type());
        }

        if (tx.getCreditCard() != null) {
            // Só a compra que já estava no cartão e já estava PAGA (pela fatura) pode continuar
            // assim; virar PAGA aqui, ou ir para o cartão já PAGA, é pagar fora da fatura.
            boolean paidOutsideInvoice = tx.getStatus() == TransactionStatus.PAID
                    && (previousStatus != TransactionStatus.PAID || !wasOnCard);
            if (paidOutsideInvoice) {
                throw new BusinessRuleException(CARD_PURCHASE_PAID_BY_INVOICE);
            }
            if (tx.getStatus() != TransactionStatus.PAID) {
                tx.setPaymentDate(null);
            }
        }

        refreshDueStatus(tx);
        if (tx.getStatus() == TransactionStatus.CANCELLED) {
            releaseReimbursement(tx);
        } else if (request.amount() != null) {
            checkReimbursementAmounts(tx);
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

        if (tx.getType() == TransactionType.INVOICE_PAYMENT) {
            deleteInvoicePayment(tx);
            return;
        }

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

        releaseReimbursement(tx);
        attachmentService.deleteAllForTransactions(List.of(tx.getId()));
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
        if (tx.getCreditCard() != null) {
            throw new BusinessRuleException(CARD_PURCHASE_PAID_BY_INVOICE);
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
        rejectInvoicePayment(tx);

        tx.setStatus(TransactionStatus.CANCELLED);
        tx.setCancelledAt(Instant.now());
        releaseReimbursement(tx);

        tx = transactionRepository.save(tx);
        creditCardService.syncInvoiceItemForTransaction(tx);
        return toDetail(tx);
    }

    @Override
    @Transactional
    public TransactionDetailResponse reactivateTransaction(UUID id, UUID userId) {
        Transaction tx = findOwnedTransaction(id, userId);

        if (tx.getStatus() != TransactionStatus.CANCELLED) {
            throw new BusinessRuleException("Só transações canceladas podem ser reativadas.");
        }
        // Parcela cancelada é resultado de quitar ou encerrar a série; reativá-la sozinha
        // desalinharia a série da fatura, como excluir uma parcela avulsa.
        if (tx.getInstallmentSeries() != null) {
            throw new BusinessRuleException("Parcelas canceladas não podem ser reativadas individualmente.");
        }

        tx.setStatus(TransactionStatus.PENDING);
        tx.setCancelledAt(null);
        if (tx.getCreditCard() != null) {
            tx.setPaymentDate(null);
        }

        tx = transactionRepository.save(tx);
        creditCardService.syncInvoiceItemForTransaction(tx);
        return toDetail(tx);
    }

    @Override
    @Transactional
    public TransactionDetailResponse setReimbursement(UUID id, ReimbursementRequest request, UUID userId) {
        Transaction tx = findOwnedTransaction(id, userId);

        if (tx.getType() != TransactionType.EXPENSE) {
            throw new BusinessRuleException("Só despesas podem ser marcadas como reembolsáveis.");
        }
        if (tx.getStatus() == TransactionStatus.CANCELLED) {
            throw new BusinessRuleException("Transações canceladas não podem ser marcadas como reembolsáveis.");
        }
        // A linha agrupada do parcelamento representa a compra inteira; marcar uma parcela só
        // deixaria a série metade fora dos gráficos.
        if (tx.getInstallmentSeries() != null) {
            throw new BusinessRuleException("Parcelas não podem ser marcadas como reembolsáveis.");
        }
        if (!request.reimbursable() && request.reimbursedById() != null) {
            throw new BusinessRuleException("Só uma despesa reembolsável pode ter o reembolso vinculado.");
        }

        Transaction income = null;
        if (request.reimbursedById() != null) {
            income = findOwnedTransaction(request.reimbursedById(), userId);
            if (!isInflow(income.getType())) {
                throw new BusinessRuleException("O reembolso precisa ser uma receita ou um reembolso.");
            }
            if (income.getStatus() == TransactionStatus.CANCELLED) {
                throw new BusinessRuleException("Uma receita cancelada não pode ser vinculada como reembolso.");
            }
            transactionRepository.findByReimbursedBy_Id(income.getId())
                    .filter(other -> !other.getId().equals(tx.getId()))
                    .ifPresent(other -> {
                        throw new BusinessRuleException(
                                "Esta receita já está vinculada como reembolso de \"" + other.getDescription() + "\".");
                    });
        }

        tx.setReimbursable(request.reimbursable());
        tx.setReimbursedBy(income);
        checkReimbursementAmounts(tx);
        return toDetail(transactionRepository.save(tx));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TransactionSummaryResponse> listPendingReimbursements(UUID userId) {
        return withReimbursements(transactionRepository.findPendingReimbursements(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TransactionSummaryResponse> listReimbursementCandidates(UUID id, UUID userId) {
        Transaction tx = findOwnedTransaction(id, userId);
        if (tx.getType() != TransactionType.EXPENSE) {
            throw new BusinessRuleException("Só despesas podem ser reembolsadas.");
        }
        return transactionRepository.findReimbursementCandidates(
                        userId, tx.getAmount(), PageRequest.of(0, REIMBURSEMENT_CANDIDATES)).stream()
                .map(this::toSummary)
                .toList();
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
                filter.includeCreditCard(),
                filter.scope(),
                pageable
        );

        Map<UUID, ReimbursementResponse> reimbursements = loadReimbursements(page.getContent());
        Set<UUID> crossScopeGroups = crossScopeTransferGroups(page.getContent());
        if (!filter.groupInstallments()) {
            return page.map(tx -> toSummary(tx).withReimbursement(reimbursements.get(tx.getId()))
                    .withCrossScope(crossScopeGroups.contains(tx.getTransferGroupId())));
        }

        Set<UUID> seriesIds = page.getContent().stream()
                .filter(tx -> tx.getInstallmentSeries() != null && tx.getInstallmentNumber() != null)
                .map(tx -> tx.getInstallmentSeries().getId())
                .collect(Collectors.toSet());

        Map<UUID, SeriesAggregate> aggregates = seriesIds.isEmpty() ? Map.of() : loadSeriesAggregates(seriesIds);
        return page.map(tx -> {
            if (tx.getInstallmentSeries() == null || tx.getInstallmentNumber() == null) {
                return toSummary(tx).withReimbursement(reimbursements.get(tx.getId()))
                        .withCrossScope(crossScopeGroups.contains(tx.getTransferGroupId()));
            }
            SeriesAggregate agg = aggregates.get(tx.getInstallmentSeries().getId());
            return agg != null ? toGroupedSummary(tx, agg) : toSummary(tx);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public TransactionTotalsResponse summarizeTransactions(TransactionFilterRequest filter, UUID userId) {
        List<Object[]> rows = transactionRepository.sumByTypeWithFilters(
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
                filter.includeCreditCard(),
                filter.scope()
        );

        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        BigDecimal invoicePayment = BigDecimal.ZERO;
        long incomeCount = 0;
        long expenseCount = 0;
        long invoicePaymentCount = 0;
        for (Object[] row : rows) {
            TransactionType type = (TransactionType) row[0];
            long count = ((Number) row[1]).longValue();
            BigDecimal sum = (BigDecimal) row[2];
            switch (type) {
                case INCOME, REFUND -> {
                    income = income.add(sum);
                    incomeCount += count;
                }
                case EXPENSE -> {
                    expense = expense.add(sum);
                    expenseCount += count;
                }
                case INVOICE_PAYMENT -> {
                    invoicePayment = invoicePayment.add(sum);
                    invoicePaymentCount += count;
                }
                default -> { }
            }
        }
        return new TransactionTotalsResponse(income, incomeCount, expense, expenseCount,
                invoicePayment, invoicePaymentCount, income.subtract(expense));
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
        LocalDate today = LocalDate.now();
        return transactionRepository.markOverdueForUser(userId, today)
                + transactionRepository.markNoLongerOverdueForUser(userId, today);
    }

    @Override
    @Transactional
    public int detectOverdueAll() {
        LocalDate today = LocalDate.now();
        return transactionRepository.markOverdueAll(today)
                + transactionRepository.markNoLongerOverdueAll(today);
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

    /**
     * O pagamento de fatura já quitou a fatura e as compras dela: alterá-lo ou cancelá-lo
     * sozinho deixaria a fatura PAGA sem o dinheiro que a pagou. Para desfazer, exclui-se
     * ({@link #deleteInvoicePayment}), que devolve o valor à fatura.
     */
    private void rejectInvoicePayment(Transaction tx) {
        if (tx.getType() == TransactionType.INVOICE_PAYMENT) {
            throw new BusinessRuleException(
                    "Pagamentos de fatura não podem ser alterados nem cancelados. Exclua o pagamento para desfazê-lo.");
        }
    }

    private void deleteInvoicePayment(Transaction tx) {
        if (tx.getPaidInvoice() == null) {
            throw new BusinessRuleException(
                    "Este pagamento é anterior ao vínculo com a fatura e não pode ser excluído.");
        }
        UUID invoiceId = tx.getPaidInvoice().getId();
        BigDecimal amount = tx.getAmount().negate();

        attachmentService.deleteAllForTransactions(List.of(tx.getId()));
        transactionRepository.delete(tx);
        transactionRepository.flush();
        creditCardService.revertInvoicePayment(invoiceId, amount);
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
            // Igual ao POST /{id}/cancel: estornar um lançamento pago é cancelá-lo.
            case PAID -> to == TransactionStatus.CANCELLED;
            case CANCELLED -> false;
        };
        if (!valid) {
            throw new BusinessRuleException(
                    "Transição de status inválida: " + from + " → " + to);
        }
    }

    private void applyCategory(Transaction tx, UUID categoryId, UUID subcategoryId, UUID userId, String description) {
        if (categoryId != null) {
            Category category = categoryResolver.resolve(categoryId, userId);
            tx.setCategory(category);
            tx.setSubcategory(categoryResolver.resolveSubcategory(subcategoryId, category, userId));
        } else if (categoryId == null && description != null && tx.getCategory() == null) {
            applyCategoryRule(tx, userId, description);
        }
    }

    private void applyCategoryRule(Transaction tx, UUID userId, String description) {
        List<CategoryRule> rules = categoryRuleRepository.findAllByUserIdAndIsActiveTrueOrderByPriorityAsc(userId);
        categoryRuleMatcher.match(rules, description, tx.getCounterpartyName()).ifPresent(rule -> {
            tx.setCategory(rule.getCategory());
            if (rule.getSubcategory() != null) {
                tx.setSubcategory(rule.getSubcategory());
            }
        });
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
                tx.getTotalInstallments(),
                tx.getCounterpartyName(),
                tx.getCounterpartyDocument()
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
                true,
                null,
                tx.getCounterpartyName(),
                tx.getCounterpartyDocument(),
                false
        );
    }

    // ── Transferência entre escopos ─────────────────────────────────────────
    // Transferência de uma conta PF para uma PJ (ou o contrário) é pró-labore ou aporte. Como toda
    // transferência, não entra em receita nem despesa; a marca é só para o front rotular.

    /** Grupos de transferência da lista cujas pernas estão em escopos diferentes, numa consulta só. */
    private Set<UUID> crossScopeTransferGroups(Collection<Transaction> transactions) {
        Set<UUID> groupIds = transactions.stream()
                .filter(tx -> tx.getType() == TransactionType.TRANSFER && tx.getTransferGroupId() != null)
                .map(Transaction::getTransferGroupId)
                .collect(Collectors.toSet());
        if (groupIds.isEmpty()) {
            return new HashSet<>(); // não Set.of(): a lista consulta contains(null) nas linhas sem grupo
        }
        return transactionRepository.findAllByTransferGroupIdIn(groupIds).stream()
                .collect(Collectors.groupingBy(Transaction::getTransferGroupId,
                        Collectors.mapping(Transaction::scope, Collectors.toSet())))
                .entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private boolean isCrossScopeTransfer(Transaction tx) {
        if (tx.getType() != TransactionType.TRANSFER || tx.getTransferGroupId() == null) {
            return false;
        }
        return transactionRepository.findAllByTransferGroupId(tx.getTransferGroupId()).stream()
                .map(Transaction::scope)
                .distinct()
                .count() > 1;
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
                cardResponse,
                tx.getPaidInvoice() != null ? tx.getPaidInvoice().getId() : null,
                reimbursementOf(tx),
                tx.getCounterpartyName(),
                tx.getCounterpartyDocument(),
                isCrossScopeTransfer(tx)
        );
    }

    // ── Reembolso ─────────────────────────────────────────────────────────────

    private static final int REIMBURSEMENT_CANDIDATES = 20;

    /** Entradas que podem reembolsar uma despesa: receita ou reembolso (o REFUND do banco). */
    private static boolean isInflow(TransactionType type) {
        return type == TransactionType.INCOME || type == TransactionType.REFUND;
    }

    /**
     * Entrada maior que a despesa deixaria a despesa negativa nos gráficos; o vínculo é recusado
     * (V42). Vale para o vínculo novo e para a edição de valor de qualquer um dos dois lados.
     */
    private void checkReimbursementAmounts(Transaction tx) {
        Transaction expense = isInflow(tx.getType())
                ? transactionRepository.findByReimbursedBy_Id(tx.getId()).orElse(null)
                : tx;
        if (expense == null || expense.getReimbursedBy() == null) {
            return;
        }
        if (expense.getReimbursedBy().getAmount().compareTo(expense.getAmount()) > 0) {
            throw new BusinessRuleException("O valor do reembolso não pode ser maior que o da despesa.");
        }
    }

    /**
     * Cancelar ou excluir um dos lados desfaz o vínculo. A despesa continua reembolsável e volta
     * para a lista de pendentes; a despesa cancelada deixa de ser reembolsável.
     */
    private void releaseReimbursement(Transaction tx) {
        if (isInflow(tx.getType())) {
            transactionRepository.findByReimbursedBy_Id(tx.getId()).ifPresent(expense -> {
                expense.setReimbursedBy(null);
                transactionRepository.saveAndFlush(expense);
            });
        } else if (tx.isReimbursable()) {
            tx.setReimbursedBy(null);
            tx.setReimbursable(false);
        }
    }

    private ReimbursementResponse reimbursementOf(Transaction tx) {
        if (tx.isReimbursable()) {
            Transaction income = tx.getReimbursedBy();
            return income == null
                    ? new ReimbursementResponse(true, null, null, null)
                    : new ReimbursementResponse(true, income.getId(), income.getDescription(), income.getAmount());
        }
        if (isInflow(tx.getType())) {
            return transactionRepository.findByReimbursedBy_Id(tx.getId())
                    .map(expense -> new ReimbursementResponse(
                            false, expense.getId(), expense.getDescription(), expense.getAmount()))
                    .orElse(null);
        }
        return null;
    }

    /** {@link #reimbursementOf} para uma página inteira, com uma consulta só para as receitas. */
    private Map<UUID, ReimbursementResponse> loadReimbursements(List<Transaction> transactions) {
        Map<UUID, ReimbursementResponse> byId = new HashMap<>();
        List<UUID> incomeIds = transactions.stream()
                .filter(tx -> isInflow(tx.getType()))
                .map(Transaction::getId)
                .toList();
        if (!incomeIds.isEmpty()) {
            for (Transaction expense : transactionRepository.findAllByReimbursedBy_IdIn(incomeIds)) {
                byId.put(expense.getReimbursedBy().getId(), new ReimbursementResponse(
                        false, expense.getId(), expense.getDescription(), expense.getAmount()));
            }
        }
        for (Transaction tx : transactions) {
            if (tx.isReimbursable()) {
                byId.put(tx.getId(), reimbursementOf(tx));
            }
        }
        return byId;
    }

    private List<TransactionSummaryResponse> withReimbursements(List<Transaction> transactions) {
        Map<UUID, ReimbursementResponse> reimbursements = loadReimbursements(transactions);
        return transactions.stream()
                .map(tx -> toSummary(tx).withReimbursement(reimbursements.get(tx.getId())))
                .toList();
    }

    /**
     * Mesma regra do job de vencidas, aplicada na hora em que a edição mexe na data: em aberto
     * com data prevista no passado é VENCIDA, senão PENDENTE. Sem isso, adiar uma vencida a
     * deixava vencida para sempre (o job só ia num sentido).
     */
    private void refreshDueStatus(Transaction tx) {
        if (tx.getStatus() != TransactionStatus.PENDING && tx.getStatus() != TransactionStatus.OVERDUE) {
            return;
        }
        LocalDate expected = tx.getPaymentDate() != null ? tx.getPaymentDate() : tx.getCompetenceDate();
        boolean late = tx.getCreditCard() == null && expected.isBefore(LocalDate.now());
        tx.setStatus(late ? TransactionStatus.OVERDUE : TransactionStatus.PENDING);
    }

}
