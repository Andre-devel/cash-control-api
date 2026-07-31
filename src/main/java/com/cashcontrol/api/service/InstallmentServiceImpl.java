package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.domain.entity.CreditCard;
import com.cashcontrol.api.domain.entity.InstallmentSeries;
import com.cashcontrol.api.domain.entity.PaymentMethod;
import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.request.AdvanceInstallmentRequest;
import com.cashcontrol.api.dto.request.CreateInstallmentRequest;
import com.cashcontrol.api.dto.request.EarlySettlementRequest;
import com.cashcontrol.api.dto.request.EditInstallmentRequest;
import com.cashcontrol.api.dto.request.EditSeriesRequest;
import com.cashcontrol.api.dto.response.CreditCardRefResponse;
import com.cashcontrol.api.dto.response.EarlySettlementResponse;
import com.cashcontrol.api.dto.response.EditSeriesResult;
import com.cashcontrol.api.dto.response.InstallmentSeriesDetailResponse;
import com.cashcontrol.api.dto.response.InstallmentSeriesResponse;
import com.cashcontrol.api.dto.response.PaymentMethodResponse;
import com.cashcontrol.api.dto.response.TagResponse;
import com.cashcontrol.api.dto.response.TransactionDetailResponse;
import com.cashcontrol.api.dto.response.TransactionSummaryResponse;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.AttachmentRepository;
import com.cashcontrol.api.repository.CategoryRepository;
import com.cashcontrol.api.repository.InstallmentSeriesRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InstallmentServiceImpl implements InstallmentService {

    private final InstallmentSeriesRepository installmentSeriesRepository;
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final AttachmentRepository attachmentRepository;
    private final TransactionService transactionService;
    private final CreditCardService creditCardService;

    @Override
    @Transactional(readOnly = true)
    public List<InstallmentSeriesResponse> listInstallmentSeries(UUID userId) {
        return installmentSeriesRepository.findAllByUserId(userId)
                .stream()
                .map(this::toSeriesResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public InstallmentSeriesDetailResponse getInstallmentSeriesDetail(UUID seriesId, UUID userId) {
        InstallmentSeries series = findOwnedSeries(seriesId, userId);
        List<TransactionSummaryResponse> installments = transactionRepository
                .findAllByInstallmentSeries_Id(seriesId)
                .stream()
                .map(transactionService::toSummary)
                .toList();
        return new InstallmentSeriesDetailResponse(toSeriesResponse(series), installments);
    }

    @Override
    @Transactional
    public InstallmentSeriesDetailResponse createInstallmentSeries(CreateInstallmentRequest request, UUID userId) {
        Account account = accountRepository.findByIdAndUserIdAndDeletedAtIsNull(request.accountId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + request.accountId()));

        if (account.getArchivedAt() != null) {
            throw new BusinessRuleException("Cannot create an installment series on an archived account.");
        }

        Category category = resolveCategory(request.categoryId());
        Category subcategory = resolveSubcategory(request.subcategoryId(), category);

        PaymentMethod paymentMethod = transactionService.resolvePaymentMethod(request.paymentMethod());
        CreditCard creditCard = transactionService.validateAndResolveCreditCard(
                request.creditCardId(), paymentMethod.getSlug(), userId);

        InstallmentSeries series = new InstallmentSeries();
        series.setUserId(userId);
        series.setAccount(account);
        series.setType(TransactionType.EXPENSE);
        series.setDescription(request.description());
        series.setTotalAmount(request.totalAmount());
        series.setTotalInstallments(request.totalInstallments());
        series.setFirstPaymentDate(request.firstPaymentDate());
        series.setCategory(category);
        series.setSubcategory(subcategory);
        series.setPaymentMethod(paymentMethod);
        series.setCreditCard(creditCard);
        series = installmentSeriesRepository.save(series);

        int n = request.totalInstallments();
        BigDecimal baseAmount = request.totalAmount().divide(BigDecimal.valueOf(n), 2, RoundingMode.DOWN);
        BigDecimal lastAmount = request.totalAmount().subtract(baseAmount.multiply(BigDecimal.valueOf(n - 1)));

        LocalDate today = LocalDate.now();
        List<Transaction> installments = new ArrayList<>(n);

        for (int i = 0; i < n; i++) {
            LocalDate paymentDate = request.firstPaymentDate().plusMonths(i);
            BigDecimal amount = (i == n - 1) ? lastAmount : baseAmount;

            TransactionStatus status = (i == 0 && !request.firstPaymentDate().isAfter(today))
                    ? TransactionStatus.PAID
                    : TransactionStatus.PENDING;

            Transaction tx = new Transaction();
            tx.setUserId(userId);
            tx.setAccount(account);
            tx.setType(TransactionType.EXPENSE);
            tx.setStatus(status);
            tx.setAmount(amount);
            tx.setDescription(request.description());
            tx.setNotes(request.notes());
            tx.setCompetenceDate(paymentDate);
            tx.setPaymentDate(status == TransactionStatus.PAID ? paymentDate : null);
            tx.setInstallmentSeries(series);
            tx.setInstallmentNumber(i + 1);
            tx.setTotalInstallments(n);
            tx.setCategory(category);
            tx.setSubcategory(subcategory);
            tx.setPaymentMethod(paymentMethod);
            tx.setCreditCard(creditCard);
            installments.add(tx);
        }

        installments = transactionRepository.saveAll(installments);
        installments.forEach(creditCardService::createInvoiceItemForTransaction);

        return new InstallmentSeriesDetailResponse(
                toSeriesResponse(series),
                installments.stream().map(transactionService::toSummary).toList()
        );
    }

    @Override
    @Transactional
    public EditSeriesResult editSeries(UUID seriesId, EditSeriesRequest request, UUID userId) {
        InstallmentSeries series = findOwnedSeries(seriesId, userId);

        if (series.isSettled()) {
            throw new BusinessRuleException("Cannot edit a settled installment series.");
        }

        Category category = resolveCategory(request.categoryId());
        Category subcategory = resolveSubcategory(request.subcategoryId(), category);
        Account account = (request.accountId() != null)
                ? accountRepository.findByIdAndUserIdAndDeletedAtIsNull(request.accountId(), userId)
                        .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + request.accountId()))
                : null;

        if (account != null && account.getArchivedAt() != null) {
            throw new BusinessRuleException("Cannot move installments to an archived account.");
        }

        PaymentMethod paymentMethod = null;
        CreditCard creditCard = null;
        boolean updatePaymentMethod = request.paymentMethod() != null || request.creditCardId() != null;
        if (updatePaymentMethod) {
            PaymentMethodSlug targetSlug = request.paymentMethod() != null
                    ? request.paymentMethod()
                    : series.getPaymentMethod().getSlug();
            paymentMethod = transactionService.resolvePaymentMethod(targetSlug);
            UUID targetCreditCardId = request.creditCardId() != null
                    ? request.creditCardId()
                    : (targetSlug == PaymentMethodSlug.CREDIT_CARD
                            ? (series.getCreditCard() != null ? series.getCreditCard().getId() : null)
                            : null);
            creditCard = transactionService.validateAndResolveCreditCard(
                    targetCreditCardId, paymentMethod.getSlug(), userId);
        }

        // Update series master record
        if (request.description() != null) {
            series.setDescription(request.description());
        }
        if (request.categoryId() != null) {
            series.setCategory(category);
            series.setSubcategory(subcategory);
        }
        if (updatePaymentMethod) {
            series.setPaymentMethod(paymentMethod);
            series.setCreditCard(creditCard);
        }
        series = installmentSeriesRepository.save(series);

        // Update all non-detached PENDING/OVERDUE installments
        List<Transaction> pending = transactionRepository.findAllByInstallmentSeries_Id(seriesId)
                .stream()
                .filter(t -> !t.isDetached())
                .filter(t -> t.getStatus() == TransactionStatus.PENDING || t.getStatus() == TransactionStatus.OVERDUE)
                .collect(Collectors.toList());

        for (Transaction tx : pending) {
            if (request.description() != null) {
                tx.setDescription(request.description());
            }
            if (request.notes() != null) {
                tx.setNotes(request.notes());
            }
            if (request.categoryId() != null) {
                tx.setCategory(category);
                tx.setSubcategory(subcategory);
            }
            if (account != null) {
                tx.setAccount(account);
            }
            if (updatePaymentMethod) {
                tx.setPaymentMethod(paymentMethod);
                tx.setCreditCard(creditCard);
            }
        }

        transactionRepository.saveAll(pending);
        pending.forEach(creditCardService::syncInvoiceItemForTransaction);

        return new EditSeriesResult(toSeriesResponse(series), pending.size());
    }

    @Override
    @Transactional
    public TransactionDetailResponse editInstallment(UUID transactionId, EditInstallmentRequest request, UUID userId) {
        Transaction tx = transactionRepository.findByIdAndUserId(transactionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + transactionId));

        if (tx.getInstallmentSeries() == null) {
            throw new BusinessRuleException("Transaction is not part of an installment series.");
        }

        if (tx.getStatus() == TransactionStatus.CANCELLED) {
            throw new BusinessRuleException("Cancelled installments cannot be edited.");
        }

        if (!tx.isDetached()) {
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
        if (request.paymentDate() != null) {
            tx.setPaymentDate(request.paymentDate());
        }
        if (request.categoryId() != null) {
            Category category = categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + request.categoryId()));
            tx.setCategory(category);
            if (request.subcategoryId() != null) {
                Category sub = categoryRepository.findById(request.subcategoryId())
                        .orElseThrow(() -> new ResourceNotFoundException("Subcategory not found: " + request.subcategoryId()));
                tx.setSubcategory(sub);
            } else {
                tx.setSubcategory(null);
            }
        }

        tx = transactionRepository.save(tx);
        creditCardService.syncInvoiceItemForTransaction(tx);
        return transactionService.toDetail(tx);
    }

    @Override
    @Transactional
    public EarlySettlementResponse earlySettlement(UUID seriesId, EarlySettlementRequest request, UUID userId) {
        InstallmentSeries series = findOwnedSeries(seriesId, userId);

        if (series.isSettled()) {
            throw new BusinessRuleException("Installment series is already settled.");
        }

        List<Transaction> remaining = transactionRepository.findAllByInstallmentSeries_Id(seriesId)
                .stream()
                .filter(t -> t.getStatus() == TransactionStatus.PENDING || t.getStatus() == TransactionStatus.OVERDUE)
                .collect(Collectors.toList());

        Instant now = Instant.now();
        for (Transaction tx : remaining) {
            tx.setStatus(TransactionStatus.CANCELLED);
            tx.setCancelledAt(now);
        }
        transactionRepository.saveAll(remaining);
        remaining.forEach(creditCardService::syncInvoiceItemForTransaction);

        Transaction settlement = new Transaction();
        settlement.setUserId(userId);
        settlement.setAccount(series.getAccount());
        settlement.setType(TransactionType.EXPENSE);
        settlement.setStatus(TransactionStatus.PAID);
        settlement.setAmount(request.settlementAmount());
        settlement.setDescription("Early settlement: " + series.getDescription());
        settlement.setCompetenceDate(request.settlementDate());
        settlement.setPaymentDate(request.settlementDate());
        settlement.setInstallmentSeries(series);
        settlement.setEarlySettlement(true);
        settlement.setCategory(series.getCategory());
        settlement.setSubcategory(series.getSubcategory());
        settlement.setPaymentMethod(series.getPaymentMethod());
        settlement.setCreditCard(series.getCreditCard());
        settlement = transactionRepository.save(settlement);
        creditCardService.createInvoiceItemForTransaction(settlement);

        series.setSettled(true);
        series.setSettledAt(now);
        installmentSeriesRepository.save(series);

        return new EarlySettlementResponse(transactionService.toDetail(settlement), remaining.size());
    }

    @Override
    @Transactional
    public List<TransactionDetailResponse> advanceInstallments(AdvanceInstallmentRequest request, UUID userId) {
        LocalDate today = LocalDate.now();
        List<Transaction> updated = new ArrayList<>();

        for (UUID installmentId : request.installmentIds()) {
            Transaction tx = transactionRepository.findByIdAndUserId(installmentId, userId)
                    .orElseThrow(() -> new ResourceNotFoundException("Installment not found: " + installmentId));

            if (tx.getStatus() != TransactionStatus.PENDING) {
                throw new BusinessRuleException(
                        "Only PENDING installments can be advanced. Installment " + installmentId
                        + " has status: " + tx.getStatus());
            }

            tx.setPaymentDate(request.newPaymentDate());

            if (request.adjustedAmount() != null) {
                tx.setAmount(request.adjustedAmount());
            }

            if (!request.newPaymentDate().isAfter(today)) {
                tx.setStatus(TransactionStatus.PAID);
            }

            updated.add(tx);
        }

        updated = transactionRepository.saveAll(updated);
        return updated.stream().map(transactionService::toDetail).toList();
    }

    @Override
    @Transactional
    public void deleteInstallmentSeries(UUID seriesId, UUID userId) {
        InstallmentSeries series = findOwnedSeries(seriesId, userId);

        if (series.isSettled()) {
            throw new BusinessRuleException(
                    "Cannot delete a settled installment series; its settlement is part of the payment history.");
        }

        List<Transaction> installments = transactionRepository.findAllByInstallmentSeries_Id(seriesId);

        boolean anyPaid = installments.stream().anyMatch(t -> t.getStatus() == TransactionStatus.PAID);
        if (anyPaid) {
            throw new BusinessRuleException(
                    "Cannot delete an installment series with paid installments. "
                    + "Use POST /api/v1/installments/series/{seriesId}/settle to cancel the remaining ones.");
        }

        List<UUID> installmentIds = installments.stream().map(Transaction::getId).toList();
        if (!installmentIds.isEmpty() && attachmentRepository.countByTransaction_IdIn(installmentIds) > 0) {
            throw new BusinessRuleException(
                    "Cannot delete an installment series whose installments have attachments. "
                    + "Remove the attachments first.");
        }

        // Rejects the deletion when any installment already landed on a closed invoice.
        creditCardService.deleteInvoiceItemsForInstallmentSeries(seriesId);

        transactionRepository.deleteAll(installments);
        installmentSeriesRepository.delete(series);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private InstallmentSeries findOwnedSeries(UUID seriesId, UUID userId) {
        return installmentSeriesRepository.findByIdAndUserId(seriesId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Installment series not found: " + seriesId));
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

    private InstallmentSeriesResponse toSeriesResponse(InstallmentSeries series) {
        return new InstallmentSeriesResponse(
                series.getId(),
                series.getAccount() != null ? series.getAccount().getId() : null,
                series.getAccount() != null ? series.getAccount().getName() : null,
                series.getType(),
                series.getDescription(),
                series.getTotalAmount(),
                series.getTotalInstallments(),
                series.getFirstPaymentDate(),
                series.getCategory() != null ? series.getCategory().getId() : null,
                series.getCategory() != null ? series.getCategory().getName() : null,
                series.isSettled(),
                series.getSettledAt(),
                series.getCreatedAt(),
                series.getUpdatedAt()
        );
    }
}
