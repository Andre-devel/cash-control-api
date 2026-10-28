package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.domain.entity.Invoice;
import com.cashcontrol.api.domain.entity.InvoiceItem;
import com.cashcontrol.api.domain.entity.InvoiceStatus;
import com.cashcontrol.api.domain.entity.MerchantAlias;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.request.UpdateInvoiceItemRequest;
import com.cashcontrol.api.dto.response.InvoiceResponse;
import com.cashcontrol.api.dto.response.InvoiceSummaryResponse;
import com.cashcontrol.api.dto.response.MerchantScopeResponse;
import com.cashcontrol.api.dto.response.UpdateInvoiceItemResponse;
import com.cashcontrol.api.repository.CreditCardRepository;
import com.cashcontrol.api.repository.InvoiceItemRepository;
import com.cashcontrol.api.repository.InvoiceRepository;
import com.cashcontrol.api.repository.MerchantAliasRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InvoiceManagementServiceImpl implements InvoiceManagementService {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final InvoiceRepository invoiceRepository;
    private final InvoiceItemRepository invoiceItemRepository;
    private final CreditCardRepository creditCardRepository;
    private final CategoryResolver categoryResolver;
    private final TransactionRepository transactionRepository;
    private final MerchantAliasRepository merchantAliasRepository;
    private final MerchantAliasService merchantAliasService;
    private final CreditCardService creditCardService;

    @Override
    @Transactional(readOnly = true)
    public Page<InvoiceSummaryResponse> listInvoices(UUID cardId, UUID userId, int page, int size) {
        creditCardRepository.findByIdAndUserIdAndDeletedAtIsNull(cardId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Credit card not found: " + cardId));

        Pageable pageable = PageRequest.of(page, size);
        Page<Invoice> invoices = invoiceRepository
                .findAllByCreditCard_IdAndUserIdOrderByReferenceMonthDesc(cardId, userId, pageable);

        List<UUID> invoiceIds = invoices.getContent().stream().map(Invoice::getId).toList();
        Map<UUID, long[]> counts = new HashMap<>();
        for (Object[] row : invoiceItemRepository.countItemsByInvoiceIds(invoiceIds)) {
            counts.put((UUID) row[0], new long[] {(Long) row[1], (Long) row[2]});
        }

        return invoices.map(invoice -> {
            long[] count = counts.getOrDefault(invoice.getId(), new long[] {0L, 0L});
            return new InvoiceSummaryResponse(
                    invoice.getId(),
                    cardId,
                    invoice.getReferenceMonth(),
                    invoice.getStatus(),
                    invoice.getClosingDate(),
                    invoice.getDueDate(),
                    invoice.getTotalAmount(),
                    invoice.getPaidAmount(),
                    invoice.isPaidWithoutTransaction(),
                    count[0],
                    count[1]
            );
        });
    }

    @Override
    @Transactional(readOnly = true)
    public InvoiceResponse getInvoiceById(UUID invoiceId, UUID userId, int page, int size) {
        Invoice invoice = requireInvoice(invoiceId, userId);

        Pageable pageable = PageRequest.of(page, size,
                Sort.by("competenceDate").descending().and(Sort.by("createdAt").descending()));
        Page<InvoiceItem> itemsPage = invoiceItemRepository.findAllByInvoice_IdAndCancelledAtIsNull(
                invoice.getId(), pageable);

        return InvoiceMapper.toInvoiceResponse(invoice, invoice.getCreditCard().getId(), page, size, itemsPage);
    }

    /**
     * O coração da tela: corrige descrição e categoria de um item, propaga para a transação
     * espelho, e opcionalmente grava a memória de apelido e/ou aplica a mesma correção aos
     * outros lançamentos do mesmo estabelecimento.
     *
     * <p>Só descrição e categoria mudam — nunca valor ou data — então não há total de fatura
     * a recalcular nem ciclo a mover, e {@code CreditCardService.syncInvoiceItemForTransaction}
     * não entra aqui: ele existe para quando o valor ou a data de competência mudam.
     */
    @Override
    @Transactional
    public UpdateInvoiceItemResponse updateItem(UUID itemId, UpdateInvoiceItemRequest request, UUID userId) {
        InvoiceItem item = invoiceItemRepository.findByIdAndUserId(itemId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice item not found: " + itemId));
        if (item.getCancelledAt() != null) {
            throw new BusinessRuleException("Não é possível editar um lançamento cancelado.");
        }

        // Capturados antes de qualquer mutação: remember() precisa da descrição original de
        // verdade (não a que está prestes a virar a nova description), e a busca por "outros
        // lançamentos do estabelecimento" precisa da identidade que o item TINHA ao abrir a
        // edição — não de uma que só existiria depois do save, no caso raro de um item sem
        // originalDescription (fallback para description em InvoiceItem.deriveMerchantKey).
        String originalDescription = item.getOriginalDescription() != null
                ? item.getOriginalDescription() : item.getDescription();
        String merchantKeyBeforeEdit = item.getMerchantKey();

        String description = request.description().trim();
        Category category = categoryResolver.resolve(request.categoryId(), userId);
        Category subcategory = category != null
                ? categoryResolver.resolveSubcategory(request.subcategoryId(), category, userId)
                : null;

        applyDescriptionAndCategory(item, description, category, subcategory);
        invoiceItemRepository.save(item);

        Transaction transaction = item.getTransaction();
        if (transaction != null) {
            applyDescriptionAndCategory(transaction, description, category, subcategory);
            transactionRepository.save(transaction);
        }

        if (request.rememberMerchant()) {
            merchantAliasService.remember(userId, originalDescription, description);
        }

        int updatedRelatedItems = 0;
        if (request.applyToHistory() && merchantKeyBeforeEdit != null) {
            List<InvoiceItem> related = invoiceItemRepository
                    .findAllByUserIdAndMerchantKeyAndCancelledAtIsNullAndIdNot(
                            userId, merchantKeyBeforeEdit, item.getId());
            for (InvoiceItem relatedItem : related) {
                applyDescriptionAndCategory(relatedItem, description, category, subcategory);
                invoiceItemRepository.save(relatedItem);
                if (relatedItem.getTransaction() != null) {
                    applyDescriptionAndCategory(relatedItem.getTransaction(), description, category, subcategory);
                    transactionRepository.save(relatedItem.getTransaction());
                }
            }
            updatedRelatedItems = related.size();
        }

        return new UpdateInvoiceItemResponse(InvoiceMapper.toItemResponse(item), updatedRelatedItems);
    }

    @Override
    @Transactional(readOnly = true)
    public MerchantScopeResponse getMerchantScope(UUID itemId, UUID userId) {
        InvoiceItem item = invoiceItemRepository.findByIdAndUserId(itemId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice item not found: " + itemId));

        String merchantKey = item.getMerchantKey();
        if (merchantKey == null) {
            return new MerchantScopeResponse(null, item.getOriginalDescription(), null, 0);
        }

        long relatedItemCount = invoiceItemRepository
                .findAllByUserIdAndMerchantKeyAndCancelledAtIsNullAndIdNot(userId, merchantKey, itemId)
                .size();
        String currentAliasName = merchantAliasRepository.findByUserIdAndMerchantKey(userId, merchantKey)
                .map(MerchantAlias::getDisplayName)
                .orElse(null);

        return new MerchantScopeResponse(merchantKey, item.getOriginalDescription(), currentAliasName, relatedItemCount);
    }

    /**
     * A quitação simples que o import de fatura já oferece via {@code alreadyPaid}
     * (ver {@code FaturaImportServiceImpl.markInvoicesPaid}) — só a fatura muda, as compras
     * continuam PENDENTES e o saldo da conta não é tocado.
     *
     * <p>Idempotente também sobre uma fatura já quitada por pagamento real: nesse caso
     * {@code paidWithoutTransaction} já é {@code false} e {@link #settle} não o sobrescreve,
     * senão "reabrir" passaria a ser oferecido para um pagamento que de fato existe.
     */
    @Override
    @Transactional
    public int refreshInvoiceStatuses() {
        LocalDate today = LocalDate.now();
        // VENCIDA antes de FECHADA: uma ABERTA que já venceu vai direto para VENCIDA.
        return invoiceRepository.markOverdue(today) + invoiceRepository.markClosed(today);
    }

    @Override
    @Transactional
    public InvoiceResponse settle(UUID invoiceId, UUID userId) {
        Invoice invoice = requireInvoice(invoiceId, userId);

        // Fatura ABERTA ainda recebe compras: quitá-la agora faria a próxima compra entrar numa
        // fatura paga. Mesma tolerância de payInvoice para o intervalo antes do job noturno.
        if (invoice.getStatus() == InvoiceStatus.OPEN && !LocalDate.now().isAfter(invoice.getClosingDate())) {
            throw new BusinessRuleException("Não é possível marcar como paga uma fatura ainda ABERTA.");
        }

        boolean paidByRealTransaction = invoice.getStatus() == InvoiceStatus.PAID && !invoice.isPaidWithoutTransaction();
        if (!paidByRealTransaction) {
            invoice.setPaidAmount(invoice.getTotalAmount());
            invoice.setStatus(InvoiceStatus.PAID);
            invoice.setPaidWithoutTransaction(true);
            invoiceRepository.save(invoice);
            // Quitada por inteiro não empurra mais nada para a seguinte.
            creditCardService.syncRevolvingItem(invoice);
            // Sem pagamento real não há data de saída do dinheiro; o vencimento é a data em
            // que a fatura foi (ou seria) paga, e coloca o gasto no mês certo do dashboard.
            creditCardService.syncInvoicePurchases(invoice, invoice.getDueDate());
        }

        return getInvoiceById(invoiceId, userId, 0, DEFAULT_PAGE_SIZE);
    }

    /**
     * Desfaz {@link #settle}. Bloqueada quando a quitação veio de um pagamento real: reabrir
     * deixaria a transação de pagamento na conta sem fatura para justificá-la — o caminho
     * nesse caso é excluir o pagamento.
     *
     * <p>Volta ao que os pagamentos reais ligados à fatura cobrem: PARCIAL se algum foi feito
     * antes da quitação, senão o status que a data dá ({@code Invoice.applyPaidAmount}).
     */
    @Override
    @Transactional
    public InvoiceResponse reopen(UUID invoiceId, UUID userId) {
        Invoice invoice = requireInvoice(invoiceId, userId);

        if (invoice.getStatus() != InvoiceStatus.PAID) {
            throw new BusinessRuleException("A fatura não está paga.");
        }
        if (!invoice.isPaidWithoutTransaction()) {
            throw new BusinessRuleException(
                    "Existe um pagamento lançado na conta. Exclua a transação antes de reabrir a fatura.");
        }

        invoice.setPaidWithoutTransaction(false);
        invoice.applyPaidAmount(transactionRepository.sumPaymentsForInvoice(invoice.getId()), LocalDate.now());
        invoiceRepository.save(invoice);
        creditCardService.syncRevolvingItem(invoice);
        creditCardService.syncInvoicePurchases(invoice, LocalDate.now());

        return getInvoiceById(invoiceId, userId, 0, DEFAULT_PAGE_SIZE);
    }

    private Invoice requireInvoice(UUID invoiceId, UUID userId) {
        return invoiceRepository.findByIdAndUserId(invoiceId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + invoiceId));
    }

    private void applyDescriptionAndCategory(InvoiceItem item, String description, Category category, Category subcategory) {
        item.setDescription(description);
        item.setCategory(category);
        item.setSubcategory(subcategory);
    }

    private void applyDescriptionAndCategory(Transaction tx, String description, Category category, Category subcategory) {
        tx.setDescription(description);
        tx.setCategory(category);
        tx.setSubcategory(subcategory);
    }
}
