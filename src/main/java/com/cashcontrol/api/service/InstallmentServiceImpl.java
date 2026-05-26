package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.domain.entity.InstallmentSeries;
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
import com.cashcontrol.api.dto.response.EarlySettlementResponse;
import com.cashcontrol.api.dto.response.EditSeriesResult;
import com.cashcontrol.api.dto.response.InstallmentSeriesDetailResponse;
import com.cashcontrol.api.dto.response.InstallmentSeriesResponse;
import com.cashcontrol.api.dto.response.TagResponse;
import com.cashcontrol.api.dto.response.TransactionDetailResponse;
import com.cashcontrol.api.dto.response.TransactionSummaryResponse;
import com.cashcontrol.api.repository.AccountRepository;
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
            installments.add(tx);
        }

        installments = transactionRepository.saveAll(installments);

        return new InstallmentSeriesDetailResponse(
                toSeriesResponse(series),
                installments.stream().map(this::toSummary).toList()
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

        // Update series master record
        if (request.description() != null) {
            series.setDescription(request.description());
        }
        if (request.categoryId() != null) {
            series.setCategory(category);
            series.setSubcategory(subcategory);
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
        }

        transactionRepository.saveAll(pending);

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
        return toDetail(tx);
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
        settlement = transactionRepository.save(settlement);

        series.setSettled(true);
        series.setSettledAt(now);
        installmentSeriesRepository.save(series);

        return new EarlySettlementResponse(toDetail(settlement), remaining.size());
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
        return updated.stream().map(this::toDetail).toList();
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
