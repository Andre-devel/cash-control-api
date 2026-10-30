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
import java.time.YearMonth;
import java.util.List;
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
    private final CategoryResolver categoryResolver;
    private final PaymentMethodRepository paymentMethodRepository;
    private final InvoiceCycleCalculator cycleCalculator;

    @Override
    public CreditCardResponse createCard(CreateCardRequest request, UUID userId) {
        if (request.closingDay() < 1 || request.closingDay() > 31) {
            throw new BusinessRuleException("closingDay deve estar entre 1 e 31.");
        }
        if (request.dueDay() < 1 || request.dueDay() > 31) {
            throw new BusinessRuleException("dueDay deve estar entre 1 e 31.");
        }

        if (creditCardRepository.existsByUserIdAndNameAndDeletedAtIsNull(userId, request.name())) {
            throw new ConflictException("Já existe um cartão de crédito com o nome '" + request.name() + "'.");
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
        card.setLast4Digits(request.last4Digits());
        card.setCreditLimit(request.creditLimit());
        card.setClosingDay(request.closingDay());
        card.setDueDay(request.dueDay());
        card.setSharedLimitGroup(sharedGroup);
        card = creditCardRepository.save(card);

        // Fatura do ciclo corrente, para o cartão já aparecer com uma aberta.
        getOrCreateInvoice(card, cycleCalculator.calculateForCharge(
                LocalDate.now(), card.getClosingDay(), card.getDueDay()));

        return toResponse(card);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CreditCardResponse> listCards(UUID userId) {
        return creditCardRepository.findAllByUserIdAndDeletedAtIsNull(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public CreditCardResponse getCard(UUID id, UUID userId) {
        return toResponse(findActiveCard(id, userId));
    }

    @Override
    public CreditCardResponse editCard(UUID id, EditCardRequest request, UUID userId) {
        CreditCard card = findActiveCard(id, userId);

        if (!card.getName().equals(request.name()) &&
                creditCardRepository.existsByUserIdAndNameAndDeletedAtIsNullAndIdNot(userId, request.name(), id)) {
            throw new ConflictException("Já existe um cartão de crédito com o nome '" + request.name() + "'.");
        }

        card.setName(request.name());
        card.setBrand(request.brand());
        card.setIssuer(request.issuer());
        card.setLast4Digits(request.last4Digits());
        card.setCreditLimit(request.creditLimit());
        boolean cycleChanged = card.getClosingDay() != request.closingDay() || card.getDueDay() != request.dueDay();
        card.setClosingDay(request.closingDay());
        card.setDueDay(request.dueDay());
        card = creditCardRepository.save(card);
        if (cycleChanged) {
            realignInvoicesToCycle(card);
        }
        return toResponse(card);
    }

    /**
     * Refaz as faturas existentes pelo ciclo novo do cartão. Cada fatura mantém o mês de
     * referência e ganha o fechamento, o vencimento e o status que esse ciclo dá.
     *
     * <p>Item importado fica na fatura em que o emissor o cobrou; só a competência, que a
     * importação deriva do ciclo, é refeita para que {@link #syncInvoiceItemForTransaction}
     * continue achando a mesma fatura numa edição futura. Lançamento manual em fatura sem
     * pagamento vai para a fatura que a data dele dá no ciclo novo. Fatura PAGA ou PARCIAL
     * não perde nem ganha lançamento manual.
     */
    private void realignInvoicesToCycle(CreditCard card) {
        LocalDate today = LocalDate.now();
        for (Invoice invoice : invoiceRepository.findAllByCreditCard_Id(card.getId())) {
            LocalDate closingDate = cycleCalculator.closingDateFor(
                    YearMonth.parse(invoice.getReferenceMonth()), card.getClosingDay());
            invoice.setClosingDate(closingDate);
            invoice.setDueDate(cycleCalculator.dueDateFor(closingDate, card.getClosingDay(), card.getDueDay()));
            invoice.refreshTimelineStatus(today);
        }

        for (InvoiceItem item : invoiceItemRepository.findAllByInvoice_CreditCard_IdAndCancelledAtIsNull(card.getId())) {
            Transaction tx = item.getTransaction();
            if (item.getExternalRef() != null) {
                LocalDate competence = cycleCalculator.competenceInCycle(
                        item.getCompetenceDate().getDayOfMonth(),
                        YearMonth.parse(item.getInvoice().getReferenceMonth()),
                        card.getClosingDay());
                item.setCompetenceDate(competence);
                if (tx != null) {
                    tx.setCompetenceDate(competence);
                }
            } else if (tx != null && !hasPayment(item.getInvoice())) {
                syncInvoiceItemForTransaction(tx);
            }
        }
    }

    private static boolean hasPayment(Invoice invoice) {
        return invoice.getStatus() == InvoiceStatus.PAID || invoice.getStatus() == InvoiceStatus.PARTIAL;
    }

    @Override
    public CreditCardResponse archiveCard(UUID id, UUID userId) {
        CreditCard card = findActiveCard(id, userId);
        if (card.getArchivedAt() != null) {
            throw new BusinessRuleException("O cartão de crédito já está arquivado.");
        }
        // Arquivado, o cartão some das telas e deixa de receber pagamento pela fatura — a
        // dívida ficaria sem caminho para ser quitada.
        BigDecimal outstanding = invoiceRepository.sumOutstandingByCardIdAndStatuses(
                card.getId(), List.of(InvoiceStatus.OPEN, InvoiceStatus.CLOSED, InvoiceStatus.OVERDUE));
        if (outstanding.signum() > 0) {
            throw new BusinessRuleException("O cartão tem faturas a pagar (R$ "
                    + outstanding.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',')
                    + "). Quite-as antes de arquivar.");
        }
        card.setArchivedAt(Instant.now());
        card = creditCardRepository.save(card);
        return toResponse(card);
    }

    @Override
    public CreditCardResponse unarchiveCard(UUID id, UUID userId) {
        CreditCard card = findActiveCard(id, userId);
        if (card.getArchivedAt() == null) {
            throw new BusinessRuleException("O cartão de crédito não está arquivado.");
        }
        card.setArchivedAt(null);
        card = creditCardRepository.save(card);
        return toResponse(card);
    }

    @Override
    public InvoiceItemResponse recordCharge(UUID cardId, RecordChargeRequest request, UUID userId) {
        CreditCard card = findActiveCard(cardId, userId);
        if (card.getArchivedAt() != null) {
            throw new BusinessRuleException("Cartões de crédito arquivados não podem receber novos lançamentos.");
        }

        InvoiceCycleInfo cycleInfo = cycleCalculator.calculateForCharge(
                request.competenceDate(), card.getClosingDay(), card.getDueDay());

        Invoice invoice = getOrCreateInvoice(card, cycleInfo);

        Category category = categoryResolver.resolve(request.categoryId(), userId);
        Category subcategory = categoryResolver.resolveSubcategory(request.subcategoryId(), category, userId);

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

        adjustInvoiceTotal(invoice, request.amount());

        return InvoiceMapper.toItemResponse(item);
    }

    @Override
    public Invoice getOrCreateInvoice(CreditCard card, InvoiceCycleInfo cycleInfo) {
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
                    // Uma compra antiga cria a fatura de um mês que já fechou (ou venceu).
                    inv.refreshTimelineStatus(LocalDate.now());
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

        return InvoiceMapper.toInvoiceResponse(invoice, card.getId(), page, size, itemsPage);
    }

    @Override
    public InvoiceResponse payInvoice(UUID invoiceId, PayInvoiceRequest request, UUID userId) {
        Invoice invoice = invoiceRepository.findByIdAndUserId(invoiceId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + invoiceId));

        if (invoice.getStatus() == InvoiceStatus.PAID) {
            throw new BusinessRuleException("A fatura já está totalmente paga.");
        }
        // O job noturno passa a fatura para FECHADA, mas pela data e não pelo status: entre a
        // virada do dia e o job ela ainda está ABERTA. Compras no próprio dia de fechamento
        // ainda entram nela (InvoiceCycleCalculator), por isso só aceita pagamento no dia seguinte.
        if (invoice.getStatus() == InvoiceStatus.OPEN && !LocalDate.now().isAfter(invoice.getClosingDate())) {
            throw new BusinessRuleException("Não é possível pagar uma fatura ainda ABERTA.");
        }

        Account sourceAccount = accountRepository.findByIdAndUserIdAndDeletedAtIsNull(
                request.sourceAccountId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + request.sourceAccountId()));
        if (sourceAccount.getArchivedAt() != null) {
            throw new BusinessRuleException(
                    "A conta de origem está arquivada e não pode ser usada para pagar faturas.");
        }

        BigDecimal remaining = invoice.getTotalAmount().subtract(invoice.getPaidAmount());
        if (request.amount().compareTo(remaining) > 0) {
            throw new BusinessRuleException(
                    "O valor informado é maior que o restante da fatura (" + remaining.toPlainString() + ").");
        }
        BigDecimal paymentAmount = request.amount();

        LocalDate paymentDate = request.paymentDate() != null ? request.paymentDate() : LocalDate.now();

        recordInvoicePayment(invoice, sourceAccount, paymentAmount, paymentDate);

        // Return updated invoice with first page of items
        return getInvoice(invoice.getCreditCard().getId(), invoice.getReferenceMonth(), userId, 0, 20);
    }

    @Override
    public Transaction recordInvoicePayment(Invoice invoice, Account sourceAccount, BigDecimal paymentAmount,
                                            LocalDate paymentDate) {
        // Saída da conta, não despesa: o gasto são as compras da fatura, marcadas como PAGAS
        // logo abaixo. Como EXPENSE, o dashboard contaria a mesma compra duas vezes.
        Transaction tx = new Transaction();
        tx.setUserId(invoice.getUserId());
        tx.setAccount(sourceAccount);
        tx.setType(TransactionType.INVOICE_PAYMENT);
        tx.setStatus(TransactionStatus.PAID);
        tx.setAmount(paymentAmount.negate());
        tx.setDescription("Pagamento de fatura — " + invoice.getCreditCard().getName()
                + " " + dueMonthLabel(invoice));
        tx.setCompetenceDate(paymentDate);
        tx.setPaymentDate(paymentDate);
        tx.setPaymentMethod(resolveDefaultPaymentMethod());
        tx.setPaidInvoice(invoice);
        transactionRepository.save(tx);

        // Quitada por "marcar como paga": o usuário já declarou o total pago e as compras já
        // estão PAGAS. O pagamento só passa a existir na conta; somá-lo ao pago dobraria a quitação.
        if (invoice.isPaidWithoutTransaction()) {
            return tx;
        }

        // Na parcial, o que faltou pagar vira item rotativo da fatura seguinte.
        invoice.applyPaidAmount(invoice.getPaidAmount().add(paymentAmount), paymentDate);
        invoiceRepository.save(invoice);
        syncRevolvingItem(invoice);
        syncInvoicePurchases(invoice, paymentDate);
        return tx;
    }

    @Override
    @Transactional(readOnly = true)
    public LimitUsageResponse getLimitUsage(UUID cardId, UUID userId) {
        CreditCard card = findActiveCard(cardId, userId);

        BigDecimal usedLimit = invoiceRepository.sumOutstandingByCardIdAndStatuses(
                cardId, List.of(InvoiceStatus.OPEN, InvoiceStatus.CLOSED, InvoiceStatus.OVERDUE));
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
        item.setAmount(invoiceAmountOf(tx));
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
        adjustInvoiceTotal(invoice, item.getAmount());
    }

    /**
     * Quanto a transação pesa na fatura. Estorno é gravado positivo, como todo REFUND, mas
     * na fatura é crédito: entra negativo e abate o total.
     */
    static BigDecimal invoiceAmountOf(Transaction tx) {
        return tx.getType() == TransactionType.REFUND ? tx.getAmount().negate() : tx.getAmount();
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
            adjustInvoiceTotal(newInvoice, invoiceAmountOf(tx));
        } else {
            BigDecimal delta = invoiceAmountOf(tx).subtract(item.getAmount());
            if (delta.compareTo(BigDecimal.ZERO) != 0) {
                adjustInvoiceTotal(currentInvoice, delta);
            }
        }

        item.setAmount(invoiceAmountOf(tx));
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
            InvoiceStatus status = item.getInvoice().getStatus();
            if (status == InvoiceStatus.PAID || status == InvoiceStatus.PARTIAL) {
                throw new BusinessRuleException(
                        "Não é possível excluir um parcelamento que alcançou uma fatura já paga (mês de referência "
                        + item.getInvoice().getReferenceMonth() + "). Use a quitação antecipada.");
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
        adjustInvoiceTotal(item.getInvoice(), item.getAmount().negate());
    }

    @Override
    public void adjustInvoiceTotal(Invoice invoice, BigDecimal delta) {
        invoice.addToTotal(delta);
        invoiceRepository.save(invoice);
        syncRevolvingItem(invoice);
    }

    @Override
    public void syncRevolvingItem(Invoice invoice) {
        BigDecimal balance = invoice.revolvingBalance();
        List<InvoiceItem> items = invoiceItemRepository
                .findAllByRevolvingFromInvoice_IdAndCancelledAtIsNullOrderByCreatedAtAsc(invoice.getId());

        // Um item só por fatura de origem. Antes do vínculo, cada pagamento parcial lançava um
        // rotativo novo com o restante inteiro; os extras são cancelados aqui.
        InvoiceItem revolving = items.isEmpty() ? null : items.getFirst();
        for (InvoiceItem extra : items.subList(Math.min(1, items.size()), items.size())) {
            extra.setCancelledAt(Instant.now());
            invoiceItemRepository.save(extra);
            adjustInvoiceTotal(extra.getInvoice(), extra.getAmount().negate());
        }

        if (balance.signum() <= 0) {
            if (revolving != null) {
                revolving.setCancelledAt(Instant.now());
                invoiceItemRepository.save(revolving);
                adjustInvoiceTotal(revolving.getInvoice(), revolving.getAmount().negate());
            }
            return;
        }

        if (revolving == null) {
            CreditCard card = invoice.getCreditCard();
            Invoice nextInvoice = getOrCreateInvoice(card, cycleCalculator.calculateForCharge(
                    invoice.getClosingDate().plusDays(1), card.getClosingDay(), card.getDueDay()));
            revolving = new InvoiceItem();
            revolving.setUserId(invoice.getUserId());
            revolving.setInvoice(nextInvoice);
            revolving.setDescription("Saldo rotativo de " + invoice.getReferenceMonth());
            revolving.setAmount(balance);
            revolving.setCompetenceDate(nextInvoice.getClosingDate().withDayOfMonth(1));
            revolving.setRevolving(true);
            revolving.setRevolvingFromInvoice(invoice);
            invoiceItemRepository.save(revolving);
            adjustInvoiceTotal(nextInvoice, balance);
            return;
        }

        BigDecimal delta = balance.subtract(revolving.getAmount());
        if (delta.signum() != 0) {
            revolving.setAmount(balance);
            invoiceItemRepository.save(revolving);
            adjustInvoiceTotal(revolving.getInvoice(), delta);
        }
    }

    @Override
    public void revertInvoicePayment(UUID invoiceId, BigDecimal amount) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + invoiceId));

        // Quitada por "marcar como paga" continua quitada: o usuário declarou o total pago,
        // e reabrir a fatura é o que desfaz essa declaração.
        if (!invoice.isPaidWithoutTransaction()) {
            invoice.applyPaidAmount(invoice.getPaidAmount().subtract(amount), LocalDate.now());
            invoiceRepository.save(invoice);
            syncRevolvingItem(invoice);
        }

        syncInvoicePurchases(invoice, LocalDate.now());
    }

    @Override
    public void syncInvoicePurchases(Invoice invoice, LocalDate paymentDate) {
        if (isSettled(invoice)) {
            transactionRepository.markInvoicePurchasesPaid(invoice.getId(), paymentDate);
        } else {
            transactionRepository.reopenInvoicePurchases(invoice.getId());
        }
        for (InvoiceItem revolving : invoiceItemRepository
                .findAllByInvoice_IdAndRevolvingFromInvoiceIsNotNullAndCancelledAtIsNull(invoice.getId())) {
            syncInvoicePurchases(revolving.getRevolvingFromInvoice(), paymentDate);
        }
    }

    /** PAGA, ou PARCIAL com o restante quitado na fatura que recebeu o rotativo. */
    private boolean isSettled(Invoice invoice) {
        if (invoice.getStatus() == InvoiceStatus.PAID) {
            return true;
        }
        if (invoice.getStatus() != InvoiceStatus.PARTIAL) {
            return false;
        }
        return invoiceItemRepository
                .findAllByRevolvingFromInvoice_IdAndCancelledAtIsNullOrderByCreatedAtAsc(invoice.getId())
                .stream().findFirst()
                .map(revolving -> isSettled(revolving.getInvoice()))
                .orElse(false);
    }

    private static final String[] MONTH_LABELS =
            {"Jan", "Fev", "Mar", "Abr", "Mai", "Jun", "Jul", "Ago", "Set", "Out", "Nov", "Dez"};

    /** {@code Nov 2026}: o mês do vencimento, que é como a interface chama a fatura. */
    private static String dueMonthLabel(Invoice invoice) {
        LocalDate due = invoice.getDueDate();
        return MONTH_LABELS[due.getMonthValue() - 1] + " " + due.getYear();
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
                card.getLast4Digits(),
                card.getCreditLimit(),
                card.getClosingDay(),
                card.getDueDay(),
                card.getSharedLimitGroup() != null ? card.getSharedLimitGroup().getId() : null,
                card.getArchivedAt(),
                card.getCreatedAt(),
                card.getUpdatedAt()
        );
    }

    private PaymentMethod resolveDefaultPaymentMethod() {
        return paymentMethodRepository.findBySlug(PaymentMethodSlug.OTHER)
                .orElseThrow(() -> new ResourceNotFoundException("Payment method not found: " + PaymentMethodSlug.OTHER));
    }
}
