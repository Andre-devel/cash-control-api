package com.cashcontrol.api;

import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.domain.entity.CreditCard;
import com.cashcontrol.api.domain.entity.Invoice;
import com.cashcontrol.api.domain.entity.InvoiceItem;
import com.cashcontrol.api.domain.entity.InvoiceStatus;
import com.cashcontrol.api.domain.entity.MerchantAlias;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.request.UpdateInvoiceItemRequest;
import com.cashcontrol.api.dto.response.InvoiceResponse;
import com.cashcontrol.api.dto.response.MerchantScopeResponse;
import com.cashcontrol.api.dto.response.UpdateInvoiceItemResponse;
import com.cashcontrol.api.repository.CategoryRepository;
import com.cashcontrol.api.repository.CreditCardRepository;
import com.cashcontrol.api.repository.InvoiceItemRepository;
import com.cashcontrol.api.repository.InvoiceRepository;
import com.cashcontrol.api.repository.MerchantAliasRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.InvoiceManagementServiceImpl;
import com.cashcontrol.api.service.MerchantAliasService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A edição de item de fatura, a quitação simples e a reabertura, isoladas do resto do import
 * — o que a tela de gerenciamento de faturas chama diretamente.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InvoiceManagementServiceTest {

    @Mock private InvoiceRepository invoiceRepository;
    @Mock private InvoiceItemRepository invoiceItemRepository;
    @Mock private CreditCardRepository creditCardRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private MerchantAliasRepository merchantAliasRepository;
    @Mock private MerchantAliasService merchantAliasService;

    private InvoiceManagementServiceImpl service;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        service = new InvoiceManagementServiceImpl(
                invoiceRepository, invoiceItemRepository, creditCardRepository,
                categoryRepository, transactionRepository, merchantAliasRepository, merchantAliasService);

        when(invoiceItemRepository.findAllByInvoice_IdAndCancelledAtIsNull(any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
    }

    // ── updateItem: memória de estabelecimento ──────────────────────────────────

    @Test
    void updateItem_rememberMerchant_savesTheAliasUnderTheOriginalDescriptionsKey_evenWhenTheItemWasAlreadyRenamed() {
        InvoiceItem item = item("claude sub", "ANTHROPIC* CLAUDE SUB", "Claude");
        when(invoiceItemRepository.findByIdAndUserId(item.getId(), userId)).thenReturn(Optional.of(item));

        UpdateInvoiceItemRequest request = new UpdateInvoiceItemRequest(
                "Claude - mensalidade", null, null, true, false);

        service.updateItem(item.getId(), request, userId);

        // A chave da memória é sempre a descrição do arquivo, não a que o item já tinha
        // antes desta edição — exatamente o que evita a circularidade documentada na V27.
        verify(merchantAliasService).remember(userId, "ANTHROPIC* CLAUDE SUB", "Claude - mensalidade");
    }

    @Test
    void updateItem_withoutRememberMerchant_doesNotTouchTheAlias() {
        InvoiceItem item = item("claude sub", "ANTHROPIC* CLAUDE SUB", "Claude");
        when(invoiceItemRepository.findByIdAndUserId(item.getId(), userId)).thenReturn(Optional.of(item));

        UpdateInvoiceItemRequest request = new UpdateInvoiceItemRequest(
                "Claude - mensalidade", null, null, false, false);

        service.updateItem(item.getId(), request, userId);

        verify(merchantAliasService, never()).remember(any(), any(), any());
    }

    @Test
    void updateItem_fallsBackToTheCurrentDescriptionWhenThereIsNoOriginal_legacyItem() {
        InvoiceItem item = item("posto", null, "POSTO 24 HORAS");
        when(invoiceItemRepository.findByIdAndUserId(item.getId(), userId)).thenReturn(Optional.of(item));

        UpdateInvoiceItemRequest request = new UpdateInvoiceItemRequest(
                "Posto da esquina", null, null, true, false);

        service.updateItem(item.getId(), request, userId);

        verify(merchantAliasService).remember(userId, "POSTO 24 HORAS", "Posto da esquina");
    }

    // ── updateItem: propagação para a transação espelho ─────────────────────────

    @Test
    void updateItem_mirrorsDescriptionAndCategoryOntoTheLinkedTransaction() {
        Category category = category("Assinaturas");
        InvoiceItem item = item("claude sub", "ANTHROPIC* CLAUDE SUB", "Claude");
        Transaction tx = new Transaction();
        item.setTransaction(tx);
        when(invoiceItemRepository.findByIdAndUserId(item.getId(), userId)).thenReturn(Optional.of(item));
        when(categoryRepository.findById(category.getId())).thenReturn(Optional.of(category));

        UpdateInvoiceItemRequest request = new UpdateInvoiceItemRequest(
                "Claude - mensalidade", category.getId(), null, false, false);

        service.updateItem(item.getId(), request, userId);

        assertThat(tx.getDescription()).isEqualTo("Claude - mensalidade");
        assertThat(tx.getCategory()).isEqualTo(category);
        verify(transactionRepository).save(tx);
    }

    @Test
    void updateItem_doesNotTouchTransactionsWhenTheItemHasNone() {
        InvoiceItem item = item("posto", "POSTO 24 HORAS", "POSTO 24 HORAS");
        when(invoiceItemRepository.findByIdAndUserId(item.getId(), userId)).thenReturn(Optional.of(item));

        service.updateItem(item.getId(),
                new UpdateInvoiceItemRequest("Posto novo", null, null, false, false), userId);

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void updateItem_rejectsAnUnknownCategory() {
        InvoiceItem item = item("posto", "POSTO 24 HORAS", "POSTO 24 HORAS");
        UUID categoryId = UUID.randomUUID();
        when(invoiceItemRepository.findByIdAndUserId(item.getId(), userId)).thenReturn(Optional.of(item));
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateItem(item.getId(),
                new UpdateInvoiceItemRequest("Posto novo", categoryId, null, false, false), userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateItem_rejectsACancelledItem() {
        InvoiceItem item = item("posto", "POSTO 24 HORAS", "POSTO 24 HORAS");
        item.setCancelledAt(java.time.Instant.now());
        when(invoiceItemRepository.findByIdAndUserId(item.getId(), userId)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.updateItem(item.getId(),
                new UpdateInvoiceItemRequest("Posto novo", null, null, false, false), userId))
                .isInstanceOf(BusinessRuleException.class);
    }

    // ── updateItem: aplicar aos outros lançamentos do estabelecimento ───────────

    @Test
    void updateItem_applyToHistory_updatesEveryOtherNonCancelledItemOfTheSameMerchant_andCountsThem() {
        InvoiceItem item = item("claude sub", "ANTHROPIC* CLAUDE SUB", "Claude");
        InvoiceItem sibling1 = item("claude sub", "ANTHROPIC* CLAUDE SUB", "Claude");
        InvoiceItem sibling2 = item("claude sub", "ANTHROPIC* CLAUDE SUB", "Claude");
        when(invoiceItemRepository.findByIdAndUserId(item.getId(), userId)).thenReturn(Optional.of(item));
        when(invoiceItemRepository.findAllByUserIdAndMerchantKeyAndCancelledAtIsNullAndIdNot(
                userId, "claude sub", item.getId()))
                .thenReturn(List.of(sibling1, sibling2));

        UpdateInvoiceItemResponse response = service.updateItem(item.getId(),
                new UpdateInvoiceItemRequest("Claude - mensalidade", null, null, false, true), userId);

        assertThat(response.updatedRelatedItems()).isEqualTo(2);
        assertThat(sibling1.getDescription()).isEqualTo("Claude - mensalidade");
        assertThat(sibling2.getDescription()).isEqualTo("Claude - mensalidade");
    }

    @Test
    void updateItem_applyToHistory_searchesByTheMerchantKeyTheItemHadBeforeThisEdit() {
        // Item sem originalDescription: o fallback de deriveMerchantKey() usa description, e a
        // chave em memória (carregada do banco) ainda é a de antes desta edição — é essa que
        // deve ser usada para achar os "outros lançamentos", não uma derivada do texto novo.
        InvoiceItem item = item("posto 24 horas", null, "POSTO 24 HORAS");
        when(invoiceItemRepository.findByIdAndUserId(item.getId(), userId)).thenReturn(Optional.of(item));

        service.updateItem(item.getId(),
                new UpdateInvoiceItemRequest("Posto da esquina", null, null, false, true), userId);

        verify(invoiceItemRepository).findAllByUserIdAndMerchantKeyAndCancelledAtIsNullAndIdNot(
                userId, "posto 24 horas", item.getId());
    }

    @Test
    void updateItem_applyToHistory_isSkippedWhenTheItemHasNoMerchantKey() {
        InvoiceItem item = item(null, "***", "***");
        when(invoiceItemRepository.findByIdAndUserId(item.getId(), userId)).thenReturn(Optional.of(item));

        UpdateInvoiceItemResponse response = service.updateItem(item.getId(),
                new UpdateInvoiceItemRequest("Descrição nova", null, null, false, true), userId);

        assertThat(response.updatedRelatedItems()).isZero();
        verify(invoiceItemRepository, never())
                .findAllByUserIdAndMerchantKeyAndCancelledAtIsNullAndIdNot(any(), any(), any());
    }

    // ── merchant scope ───────────────────────────────────────────────────────────

    @Test
    void getMerchantScope_reportsTheCurrentAliasAndHowManyOtherItemsShareTheMerchant() {
        InvoiceItem item = item("claude sub", "ANTHROPIC* CLAUDE SUB", "Claude");
        when(invoiceItemRepository.findByIdAndUserId(item.getId(), userId)).thenReturn(Optional.of(item));
        when(invoiceItemRepository.findAllByUserIdAndMerchantKeyAndCancelledAtIsNullAndIdNot(
                userId, "claude sub", item.getId()))
                .thenReturn(List.of(item("claude sub", "x", "x"), item("claude sub", "x", "x")));
        MerchantAlias alias = new MerchantAlias();
        alias.setDisplayName("Claude - mensalidade");
        when(merchantAliasRepository.findByUserIdAndMerchantKey(userId, "claude sub"))
                .thenReturn(Optional.of(alias));

        MerchantScopeResponse scope = service.getMerchantScope(item.getId(), userId);

        assertThat(scope.relatedItemCount()).isEqualTo(2);
        assertThat(scope.currentAliasName()).isEqualTo("Claude - mensalidade");
        assertThat(scope.originalDescription()).isEqualTo("ANTHROPIC* CLAUDE SUB");
    }

    // ── settle / reopen ──────────────────────────────────────────────────────────

    @Test
    void settle_marksTheInvoicePaidWithoutTouchingAnyTransaction() {
        Invoice invoice = invoice(InvoiceStatus.CLOSED, false, new BigDecimal("120.00"), BigDecimal.ZERO);
        when(invoiceRepository.findByIdAndUserId(invoice.getId(), userId)).thenReturn(Optional.of(invoice));

        service.settle(invoice.getId(), userId);

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(invoice.getPaidAmount()).isEqualByComparingTo("120.00");
        assertThat(invoice.isPaidWithoutTransaction()).isTrue();
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void settle_doesNotFlipTheFlagWhenTheInvoiceWasAlreadyPaidByARealTransaction() {
        // paidWithoutTransaction=false com status PAID só acontece via payInvoice de verdade.
        // Reimportar a mesma fatura marcando "já paga" não pode revogar isso, senão "reabrir"
        // passaria a ser oferecido para um pagamento que de fato existe.
        Invoice invoice = invoice(InvoiceStatus.PAID, false, new BigDecimal("120.00"), new BigDecimal("120.00"));
        when(invoiceRepository.findByIdAndUserId(invoice.getId(), userId)).thenReturn(Optional.of(invoice));

        service.settle(invoice.getId(), userId);

        assertThat(invoice.isPaidWithoutTransaction()).isFalse();
        verify(invoiceRepository, never()).save(invoice);
    }

    @Test
    void reopen_restoresAnInvoiceSettledWithoutATransaction() {
        Invoice invoice = invoice(InvoiceStatus.PAID, true, new BigDecimal("120.00"), new BigDecimal("120.00"));
        when(invoiceRepository.findByIdAndUserId(invoice.getId(), userId)).thenReturn(Optional.of(invoice));

        service.reopen(invoice.getId(), userId);

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.OPEN);
        assertThat(invoice.getPaidAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(invoice.isPaidWithoutTransaction()).isFalse();
    }

    @Test
    void reopen_refusesAnInvoicePaidByARealTransaction() {
        Invoice invoice = invoice(InvoiceStatus.PAID, false, new BigDecimal("120.00"), new BigDecimal("120.00"));
        when(invoiceRepository.findByIdAndUserId(invoice.getId(), userId)).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> service.reopen(invoice.getId(), userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Exclua a transação");
    }

    @Test
    void reopen_refusesAnInvoiceThatIsNotPaid() {
        Invoice invoice = invoice(InvoiceStatus.OPEN, false, new BigDecimal("120.00"), BigDecimal.ZERO);
        when(invoiceRepository.findByIdAndUserId(invoice.getId(), userId)).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> service.reopen(invoice.getId(), userId))
                .isInstanceOf(BusinessRuleException.class);
    }

    // ── apoio ─────────────────────────────────────────────────────────────────

    private InvoiceItem item(String merchantKey, String originalDescription, String description) {
        InvoiceItem item = new InvoiceItem();
        ReflectionTestUtils.setField(item, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(item, "merchantKey", merchantKey);
        item.setUserId(userId);
        item.setOriginalDescription(originalDescription);
        item.setDescription(description);
        item.setAmount(new BigDecimal("10.00"));
        item.setCompetenceDate(LocalDate.now());
        return item;
    }

    private Category category(String name) {
        Category category = new Category();
        ReflectionTestUtils.setField(category, "id", UUID.randomUUID());
        category.setName(name);
        return category;
    }

    private Invoice invoice(InvoiceStatus status, boolean paidWithoutTransaction,
                            BigDecimal totalAmount, BigDecimal paidAmount) {
        CreditCard card = new CreditCard();
        ReflectionTestUtils.setField(card, "id", UUID.randomUUID());
        Invoice invoice = new Invoice();
        ReflectionTestUtils.setField(invoice, "id", UUID.randomUUID());
        invoice.setUserId(userId);
        invoice.setCreditCard(card);
        invoice.setStatus(status);
        invoice.setPaidWithoutTransaction(paidWithoutTransaction);
        invoice.setReferenceMonth("2026-07");
        invoice.setClosingDate(LocalDate.of(2026, 7, 20));
        invoice.setDueDate(LocalDate.of(2026, 7, 27));
        invoice.setTotalAmount(totalAmount);
        invoice.setPaidAmount(paidAmount);
        return invoice;
    }
}
