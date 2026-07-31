package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.domain.entity.CreditCard;
import com.cashcontrol.api.domain.entity.Invoice;
import com.cashcontrol.api.domain.entity.InvoiceItem;
import com.cashcontrol.api.domain.entity.InvoiceStatus;
import com.cashcontrol.api.domain.entity.PaymentMethod;
import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
import com.cashcontrol.api.domain.entity.SharedLimitGroup;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ConflictException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.request.CreateCardRequest;
import com.cashcontrol.api.dto.request.EditCardRequest;
import com.cashcontrol.api.dto.request.PayInvoiceRequest;
import com.cashcontrol.api.dto.request.RecordChargeRequest;
import com.cashcontrol.api.dto.response.CreditCardResponse;
import com.cashcontrol.api.dto.response.InvoiceItemResponse;
import com.cashcontrol.api.dto.response.InvoiceResponse;
import com.cashcontrol.api.dto.response.LimitUsageResponse;
import com.cashcontrol.api.dto.response.SpendingByCategoryResponse;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.CategoryRepository;
import com.cashcontrol.api.repository.CreditCardRepository;
import com.cashcontrol.api.repository.InvoiceItemRepository;
import com.cashcontrol.api.repository.InvoiceRepository;
import com.cashcontrol.api.repository.PaymentMethodRepository;
import com.cashcontrol.api.repository.SharedLimitGroupRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.InvoiceCycleCalculator.InvoiceCycleInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class CreditCardServiceImpl implements CreditCardService {

    private final CreditCardRepository creditCardRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceItemRepository invoiceItemRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final SharedLimitGroupRepository sharedLimitGroupRepository;
    private final CategoryRepository categoryRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final InvoiceCycleCalculator cycleCalculator;

    @Override
    public CreditCardResponse createCard(CreateCardRequest request, UUID userId) {
        if (request.closingDay() < 1 || request.closingDay() > 28) {
            throw new BusinessRuleException("closingDay must be between 1 and 28.");
        }
        if (request.dueDay() < 1 || request.dueDay() > 28) {
            throw new BusinessRuleException("dueDay must be between 1 and 28.");
        }

        if (creditCardRepository.existsByUserIdAndNameAndDeletedAtIsNull(userId, request.name())) {
            throw new ConflictException("A credit card with the name '" + request.name() + "' already exists.");
        }

        SharedLimitGroup sharedGroup = null;
        if (request.sharedLimitGroupId() != null) {
            sharedGroup = sharedLimitGroupRepository.findByIdAndUserId(request.sharedLimitGroupId(), userId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Shared limit group not found: " + request.sharedLimitGroupId()));
        }

        CreditCard card = new CreditCard();
        card.setUserId(userId);
        card.setName(request.name());
        card.setBrand(request.brand());
        card.setIssuer(request.issuer());
        card.setCreditLimit(request.creditLimit());
        card.setClosingDay(request.closingDay());
        card.setDueDay(request.dueDay());
        card.setSharedLimitGroup(sharedGroup);
        card = creditCardRepository.save(card);

        // Create first OPEN invoice for the current billing cycle
        createOpenInvoiceFor(card, LocalDate.now());

        return toResponse(card);
    }

    private Invoice createOpenInvoiceFor(CreditCard card, LocalDate referenceDate) {
        InvoiceCycleInfo cycleInfo = cycleCalculator.calculateForCharge(
                referenceDate, card.getClosingDay(), card.getDueDay());

        // Don't create duplicate
        Optional<Invoice> existing = invoiceRepository.findByCreditCard_IdAndReferenceMonth(
                card.getId(), cycleInfo.referenceMonth());
        if (existing.isPresent()) {
            return existing.get();
        }

        Invoice invoice = new Invoice();
        invoice.setUserId(card.getUserId());
        invoice.setCreditCard(card);
        invoice.setStatus(InvoiceStatus.OPEN);
        invoice.setReferenceMonth(cycleInfo.referenceMonth());
        invoice.setClosingDate(cycleInfo.closingDate());
        invoice.setDueDate(cycleInfo.dueDate());
        invoice.setTotalAmount(BigDecimal.ZERO);
        invoice.setPaidAmount(BigDecimal.ZERO);
        return invoiceRepository.save(invoice);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CreditCardResponse> listCards(UUID userId) {
        return creditCardRepository.findAllByUserIdAndDeletedAtIsNull(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public CreditCardResponse editCard(UUID id, EditCardRequest request, UUID userId) {
        CreditCard card = findActiveCard(id, userId);

        if (!card.getName().equals(request.name()) &&
                creditCardRepository.existsByUserIdAndNameAndDeletedAtIsNullAndIdNot(userId, request.name(), id)) {
            throw new ConflictException("A credit card with the name '" + request.name() + "' already exists.");
        }

        card.setName(request.name());
        card.setBrand(request.brand());
        card.setIssuer(request.issuer());
        card.setCreditLimit(request.creditLimit());
        card.setClosingDay(request.closingDay());
        card.setDueDay(request.dueDay());
        card = creditCardRepository.save(card);
        return toResponse(card);
    }

    @Override
    public CreditCardResponse archiveCard(UUID id, UUID userId) {
        CreditCard card = findActiveCard(id, userId);
        if (card.getArchivedAt() != null) {
            throw new BusinessRuleException("Credit card is already archived.");
        }
        card.setArchivedAt(Instant.now());
        card = creditCardRepository.save(card);
        return toResponse(card);
    }

    @Override
    public InvoiceItemResponse recordCharge(UUID cardId, RecordChargeRequest request, UUID userId) {
        CreditCard card = findActiveCard(cardId, userId);
        if (card.getArchivedAt() != null) {
            throw new BusinessRuleException("Archived credit cards cannot receive new charges.");
        }

        InvoiceCycleInfo cycleInfo = cycleCalculator.calculateForCharge(
                request.competenceDate(), card.getClosingDay(), card.getDueDay());

        Invoice invoice = getOrCreateInvoice(card, cycleInfo);

        Category category = null;
        if (request.categoryId() != null) {
            category = categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + request.categoryId()));
        }
        Category subcategory = null;
        if (request.subcategoryId() != null) {
            subcategory = categoryRepository.findById(request.subcategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Subcategory not found: " + request.subcategoryId()));
        }

        InvoiceItem item = new InvoiceItem();
        item.setUserId(userId);
        item.setInvoice(invoice);
        item.setDescription(request.description());
        item.setAmount(request.amount());
        item.setCompetenceDate(request.competenceDate());
        item.setCategory(category);
        item.setSubcategory(subcategory);
        item.setNotes(request.notes());
        item = invoiceItemRepository.save(item);

        // Update invoice total
        invoice.setTotalAmount(invoice.getTotalAmount().add(request.amount()));
        invoiceRepository.save(invoice);

        return toItemResponse(item);
    }

    private Invoice getOrCreateInvoice(CreditCard card, InvoiceCycleInfo cycleInfo) {
        return invoiceRepository.findByCreditCard_IdAndReferenceMonth(card.getId(), cycleInfo.referenceMonth())
                .orElseGet(() -> {
                    Invoice inv = new Invoice();
                    inv.setUserId(card.getUserId());
                    inv.setCreditCard(card);
                    inv.setStatus(InvoiceStatus.OPEN);
                    inv.setReferenceMonth(cycleInfo.referenceMonth());
                    inv.setClosingDate(cycleInfo.closingDate());
                    inv.setDueDate(cycleInfo.dueDate());
                    inv.setTotalAmount(BigDecimal.ZERO);
                    inv.setPaidAmount(BigDecimal.ZERO);
                    return invoiceRepository.save(inv);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public InvoiceResponse getInvoice(UUID cardId, String referenceMonth, UUID userId, int page, int size) {
        CreditCard card = findActiveCard(cardId, userId);
        Invoice invoice = invoiceRepository.findByCreditCard_IdAndReferenceMonth(card.getId(), referenceMonth)
                .filter(inv -> inv.getUserId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found for " + referenceMonth));

        Pageable pageable = PageRequest.of(page, size,
                Sort.by("competenceDate").descending().and(Sort.by("createdAt").descending()));
        Page<InvoiceItem> itemsPage = invoiceItemRepository.findAllByInvoice_IdAndCancelledAtIsNull(
                invoice.getId(), pageable);

        List<InvoiceItemResponse> itemResponses = itemsPage.getContent().stream()
                .map(this::toItemResponse)
                .toList();

        return new InvoiceResponse(
                invoice.getId(),
                card.getId(),
                invoice.getReferenceMonth(),
                invoice.getStatus(),
                invoice.getClosingDate(),
                invoice.getDueDate(),
                invoice.getTotalAmount(),
                invoice.getPaidAmount(),
                page,
                size,
                itemsPage.getTotalElements(),
                itemResponses,
                invoice.getCreatedAt(),
                invoice.getUpdatedAt()
        );
    }

    @Override
    public InvoiceResponse payInvoice(UUID invoiceId, PayInvoiceRequest request, UUID userId) {
        Invoice invoice = invoiceRepository.findByIdAndUserId(invoiceId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + invoiceId));

        if (invoice.getStatus() == InvoiceStatus.PAID) {
            throw new BusinessRuleException("Invoice is already fully paid.");
        }
        if (invoice.getStatus() == InvoiceStatus.OPEN) {
            throw new BusinessRuleException("Cannot pay an invoice that is still OPEN.");
        }

        Account sourceAccount = accountRepository.findByIdAndUserIdAndDeletedAtIsNull(
                request.sourceAccountId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + request.sourceAccountId()));
        if (sourceAccount.getArchivedAt() != null) {
            throw new BusinessRuleException(
                    "Source account is archived and cannot be used for invoice payment.");
        }

        BigDecimal remaining = invoice.getTotalAmount().subtract(invoice.getPaidAmount());
        BigDecimal paymentAmount = request.amount().min(remaining);

        LocalDate paymentDate = request.paymentDate() != null ? request.paymentDate() : LocalDate.now();

        // Create EXPENSE transaction on source account
        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setAccount(sourceAccount);
        tx.setType(TransactionType.EXPENSE);
        tx.setStatus(TransactionStatus.PAID);
        tx.setAmount(paymentAmount);
        tx.setDescription("Invoice payment — " + invoice.getCreditCard().getName()
                + " " + invoice.getReferenceMonth());
        tx.setCompetenceDate(paymentDate);
        tx.setPaymentDate(paymentDate);
        tx.setPaymentMethod(resolveDefaultPaymentMethod());
        transactionRepository.save(tx);

        BigDecimal newPaidAmount = invoice.getPaidAmount().add(paymentAmount);
        invoice.setPaidAmount(newPaidAmount);

        if (newPaidAmount.compareTo(invoice.getTotalAmount()) >= 0) {
            invoice.setStatus(InvoiceStatus.PAID);
        } else {
            invoice.setStatus(InvoiceStatus.PARTIAL);

            // Create revolving item on next invoice for the remainder
            BigDecimal revolvingAmount = invoice.getTotalAmount().subtract(newPaidAmount);

            InvoiceCycleInfo nextCycleInfo = cycleCalculator.calculateForCharge(
                    invoice.getClosingDate().plusDays(1),
                    invoice.getCreditCard().getClosingDay(),
                    invoice.getCreditCard().getDueDay()
            );
            Invoice nextInvoice = getOrCreateInvoice(invoice.getCreditCard(), nextCycleInfo);

            InvoiceItem revolving = new InvoiceItem();
            revolving.setUserId(userId);
            revolving.setInvoice(nextInvoice);
            revolving.setDescription("Revolving balance from " + invoice.getReferenceMonth());
            revolving.setAmount(revolvingAmount);
            revolving.setCompetenceDate(nextInvoice.getClosingDate().withDayOfMonth(1));
            revolving.setRevolving(true);
            invoiceItemRepository.save(revolving);

            nextInvoice.setTotalAmount(nextInvoice.getTotalAmount().add(revolvingAmount));
            invoiceRepository.save(nextInvoice);
        }

        invoiceRepository.save(invoice);

        // Return updated invoice with first page of items
        return getInvoice(invoice.getCreditCard().getId(), invoice.getReferenceMonth(), userId, 0, 20);
    }

    @Override
    @Transactional(readOnly = true)
    public LimitUsageResponse getLimitUsage(UUID cardId, UUID userId) {
        CreditCard card = findActiveCard(cardId, userId);

        BigDecimal usedLimit = invoiceItemRepository.sumAmountByCardIdAndInvoiceStatuses(
                cardId, List.of(InvoiceStatus.OPEN, InvoiceStatus.CLOSED,
                        InvoiceStatus.PARTIAL, InvoiceStatus.OVERDUE));
        if (usedLimit == null) {
            usedLimit = BigDecimal.ZERO;
        }

        BigDecimal creditLimit = card.getCreditLimit();
        if (card.getSharedLimitGroup() != null) {
            creditLimit = card.getSharedLimitGroup().getTotalLimit();
        }

        BigDecimal availableLimit = creditLimit.subtract(usedLimit);
        if (availableLimit.compareTo(BigDecimal.ZERO) < 0) {
            availableLimit = BigDecimal.ZERO;
        }

        return new LimitUsageResponse(cardId, creditLimit, usedLimit, availableLimit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SpendingByCategoryResponse> getSpendingByCategory(
            UUID cardId, LocalDate from, LocalDate to, UUID userId) {
        findActiveCard(cardId, userId); // ownership check

        List<Object[]> rows = invoiceItemRepository.findSpendingByCategory(cardId, userId, from, to);

        BigDecimal total = rows.stream()
                .map(r -> (BigDecimal) r[2])
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return rows.stream().map(r -> {
            UUID catId = (UUID) r[0];
            String catName = (String) r[1];
            BigDecimal amount = (BigDecimal) r[2];
            BigDecimal percentage = total.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : amount.multiply(new BigDecimal("100"))
                            .divide(total, 2, RoundingMode.HALF_UP);
            return new SpendingByCategoryResponse(catId, catName, amount, percentage);
        }).toList();
    }

    @Override
    public void createInvoiceItemForTransaction(Transaction tx) {
        if (tx.getCreditCard() == null) return;
        CreditCard card = tx.getCreditCard();
        InvoiceCycleInfo cycle = cycleCalculator.calculateForCharge(
                tx.getCompetenceDate(), card.getClosingDay(), card.getDueDay());
        Invoice invoice = getOrCreateInvoice(card, cycle);

        InvoiceItem item = new InvoiceItem();
        item.setUserId(tx.getUserId());
        item.setInvoice(invoice);
        item.setTransaction(tx);
        item.setDescription(tx.getDescription());
        item.setAmount(tx.getAmount());
        item.setCompetenceDate(tx.getCompetenceDate());
        item.setCategory(tx.getCategory());
        item.setSubcategory(tx.getSubcategory());
        item.setNotes(tx.getNotes());
        if (tx.getInstallmentSeries() != null) {
            item.setInstallmentSeries(tx.getInstallmentSeries());
            item.setInstallmentNumber(tx.getInstallmentNumber());
            item.setTotalInstallments(tx.getTotalInstallments());
        }
        invoiceItemRepository.save(item);
        invoice.setTotalAmount(invoice.getTotalAmount().add(tx.getAmount()));
        invoiceRepository.save(invoice);
    }

    @Override
    public void syncInvoiceItemForTransaction(Transaction tx) {
        if (tx.getStatus() == TransactionStatus.CANCELLED) {
            cancelLinkedInvoiceItem(tx.getId());
            return;
        }

        java.util.Optional<InvoiceItem> itemOpt = invoiceItemRepository.findByTransaction_Id(tx.getId());

        if (tx.getCreditCard() == null) {
            itemOpt.ifPresent(item -> {
                if (item.getCancelledAt() != null) return;
                subtractFromInvoice(item);
                item.setCancelledAt(Instant.now());
                invoiceItemRepository.save(item);
            });
            return;
        }

        CreditCard card = tx.getCreditCard();

        if (itemOpt.isEmpty()) {
            createInvoiceItemForTransaction(tx);
            return;
        }

        InvoiceItem item = itemOpt.get();
        if (item.getCancelledAt() != null) {
            item.setTransaction(null);
            invoiceItemRepository.save(item);
            createInvoiceItemForTransaction(tx);
            return;
        }

        Invoice currentInvoice = item.getInvoice();
        InvoiceCycleInfo newCycle = cycleCalculator.calculateForCharge(
                tx.getCompetenceDate(), card.getClosingDay(), card.getDueDay());

        boolean sameCycle = currentInvoice.getCreditCard().getId().equals(card.getId())
                && currentInvoice.getReferenceMonth().equals(newCycle.referenceMonth());

        if (!sameCycle) {
            subtractFromInvoice(item);
            Invoice newInvoice = getOrCreateInvoice(card, newCycle);
            item.setInvoice(newInvoice);
            newInvoice.setTotalAmount(newInvoice.getTotalAmount().add(tx.getAmount()));
            invoiceRepository.save(newInvoice);
        } else {
            BigDecimal delta = tx.getAmount().subtract(item.getAmount());
            if (delta.compareTo(BigDecimal.ZERO) != 0) {
                currentInvoice.setTotalAmount(currentInvoice.getTotalAmount().add(delta));
                invoiceRepository.save(currentInvoice);
            }
        }

        item.setAmount(tx.getAmount());
        item.setDescription(tx.getDescription());
        item.setNotes(tx.getNotes());
        item.setCompetenceDate(tx.getCompetenceDate());
        item.setCategory(tx.getCategory());
        item.setSubcategory(tx.getSubcategory());
        invoiceItemRepository.save(item);
    }

    @Override
    public void detachInvoiceItemForTransaction(UUID transactionId) {
        invoiceItemRepository.findByTransaction_Id(transactionId).ifPresent(item -> {
            if (item.getCancelledAt() == null) {
                subtractFromInvoice(item);
                item.setCancelledAt(Instant.now());
            }
            item.setTransaction(null);
            invoiceItemRepository.save(item);
        });
    }

    @Override
    public void deleteInvoiceItemsForInstallmentSeries(UUID installmentSeriesId) {
        List<InvoiceItem> items = invoiceItemRepository.findAllByInstallmentSeries_Id(installmentSeriesId);

        for (InvoiceItem item : items) {
            if (item.getInvoice().getStatus() != InvoiceStatus.OPEN) {
                throw new BusinessRuleException(
                        "Cannot delete an installment series that reached a closed invoice (reference month "
                        + item.getInvoice().getReferenceMonth() + "). Use early settlement instead.");
            }
        }

        for (InvoiceItem item : items) {
            if (item.getCancelledAt() == null) {
                subtractFromInvoice(item);
            }
        }
        invoiceItemRepository.deleteAll(items);
    }

    private void cancelLinkedInvoiceItem(UUID transactionId) {
        invoiceItemRepository.findByTransaction_Id(transactionId).ifPresent(item -> {
            if (item.getCancelledAt() != null) return;
            subtractFromInvoice(item);
            item.setCancelledAt(Instant.now());
            invoiceItemRepository.save(item);
        });
    }

    private void subtractFromInvoice(InvoiceItem item) {
        Invoice invoice = item.getInvoice();
        invoice.setTotalAmount(invoice.getTotalAmount().subtract(item.getAmount()));
        invoiceRepository.save(invoice);
    }

    private CreditCard findActiveCard(UUID id, UUID userId) {
        return creditCardRepository.findByIdAndUserIdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Credit card not found: " + id));
    }

    private CreditCardResponse toResponse(CreditCard card) {
        return new CreditCardResponse(
                card.getId(),
                card.getName(),
                card.getBrand(),
                card.getIssuer(),
                card.getCreditLimit(),
                card.getClosingDay(),
                card.getDueDay(),
                card.getSharedLimitGroup() != null ? card.getSharedLimitGroup().getId() : null,
                card.getArchivedAt(),
                card.getCreatedAt(),
                card.getUpdatedAt()
        );
    }

    private InvoiceItemResponse toItemResponse(InvoiceItem item) {
        return new InvoiceItemResponse(
                item.getId(),
                item.getDescription(),
                item.getAmount(),
                item.getCompetenceDate(),
                item.getCategory() != null ? item.getCategory().getId() : null,
                item.getCategory() != null ? item.getCategory().getName() : null,
                item.getSubcategory() != null ? item.getSubcategory().getId() : null,
                item.getSubcategory() != null ? item.getSubcategory().getName() : null,
                item.getNotes(),
                item.isRevolving(),
                item.getInstallmentNumber(),
                item.getTotalInstallments(),
                item.getTransaction() != null ? item.getTransaction().getId() : null,
                item.getCancelledAt(),
                item.getCreatedAt(),
                item.getUpdatedAt()
        );
    }

    private PaymentMethod resolveDefaultPaymentMethod() {
        return paymentMethodRepository.findBySlug(PaymentMethodSlug.OTHER)
                .orElseThrow(() -> new ResourceNotFoundException("Payment method not found: " + PaymentMethodSlug.OTHER));
    }
}
