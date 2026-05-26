package com.cashcontrol.api;

import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.domain.entity.CategoryRule;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.request.CreateTransactionRequest;
import com.cashcontrol.api.dto.request.EditTransactionRequest;
import com.cashcontrol.api.dto.request.MarkAsPaidRequest;
import com.cashcontrol.api.dto.request.TransactionFilterRequest;
import com.cashcontrol.api.dto.response.TransactionDetailResponse;
import com.cashcontrol.api.dto.response.TransactionSummaryResponse;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.CategoryRepository;
import com.cashcontrol.api.repository.CategoryRuleRepository;
import com.cashcontrol.api.repository.TagRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.TransactionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private CategoryRuleRepository categoryRuleRepository;
    @Mock private TagRepository tagRepository;
    @InjectMocks private TransactionServiceImpl transactionService;

    private UUID userId;
    private UUID accountId;
    private Account account;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        account = new Account();
        ReflectionTestUtils.setField(account, "id", accountId);
        account.setUserId(userId);
        account.setName("Checking");
        account.setType(AccountType.CHECKING);
        account.setCurrencyCode("BRL");
    }

    // ── createTransaction ─────────────────────────────────────────────────────

    @Test
    void createTransaction_income_success() {
        when(accountRepository.findByIdAndUserIdAndDeletedAtIsNull(accountId, userId))
                .thenReturn(Optional.of(account));
        when(categoryRuleRepository.findAllByUserIdAndIsActiveTrueOrderByPriorityAsc(userId))
                .thenReturn(Collections.emptyList());
        when(transactionRepository.save(any())).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(t, "createdAt", Instant.now());
            ReflectionTestUtils.setField(t, "updatedAt", Instant.now());
            return t;
        });

        CreateTransactionRequest request = new CreateTransactionRequest(
                accountId, TransactionType.INCOME, new BigDecimal("1500.00"),
                "Salary", LocalDate.now(), null, null, null, null, null, null, null);

        TransactionDetailResponse response = transactionService.createTransaction(request, userId);

        assertThat(response.type()).isEqualTo(TransactionType.INCOME);
        assertThat(response.amount()).isEqualByComparingTo(new BigDecimal("1500.00"));
        assertThat(response.status()).isEqualTo(TransactionStatus.PAID);
        assertThat(response.paymentDate()).isEqualTo(LocalDate.now());
    }

    @Test
    void createTransaction_pendingStatus_noPaymentDate() {
        when(accountRepository.findByIdAndUserIdAndDeletedAtIsNull(accountId, userId))
                .thenReturn(Optional.of(account));
        when(categoryRuleRepository.findAllByUserIdAndIsActiveTrueOrderByPriorityAsc(userId))
                .thenReturn(Collections.emptyList());
        when(transactionRepository.save(any())).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(t, "createdAt", Instant.now());
            ReflectionTestUtils.setField(t, "updatedAt", Instant.now());
            return t;
        });

        CreateTransactionRequest request = new CreateTransactionRequest(
                accountId, TransactionType.EXPENSE, new BigDecimal("200.00"),
                "Rent", LocalDate.now(), null, null, null, null, null, null, TransactionStatus.PENDING);

        TransactionDetailResponse response = transactionService.createTransaction(request, userId);

        assertThat(response.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(response.paymentDate()).isNull();
    }

    @Test
    void createTransaction_transferType_rejected() {
        CreateTransactionRequest request = new CreateTransactionRequest(
                accountId, TransactionType.TRANSFER, new BigDecimal("100.00"),
                "Transfer", LocalDate.now(), null, null, null, null, null, null, null);

        assertThatThrownBy(() -> transactionService.createTransaction(request, userId))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void createTransaction_manualAdjustmentType_rejected() {
        CreateTransactionRequest request = new CreateTransactionRequest(
                accountId, TransactionType.MANUAL_ADJUSTMENT, new BigDecimal("100.00"),
                "Adjust", LocalDate.now(), null, null, null, null, null, null, null);

        assertThatThrownBy(() -> transactionService.createTransaction(request, userId))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void createTransaction_archivedAccount_rejected() {
        account.setArchivedAt(Instant.now());
        when(accountRepository.findByIdAndUserIdAndDeletedAtIsNull(accountId, userId))
                .thenReturn(Optional.of(account));

        CreateTransactionRequest request = new CreateTransactionRequest(
                accountId, TransactionType.EXPENSE, new BigDecimal("50.00"),
                "Coffee", LocalDate.now(), null, null, null, null, null, null, null);

        assertThatThrownBy(() -> transactionService.createTransaction(request, userId))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void createTransaction_accountNotFound_throws() {
        when(accountRepository.findByIdAndUserIdAndDeletedAtIsNull(accountId, userId))
                .thenReturn(Optional.empty());

        CreateTransactionRequest request = new CreateTransactionRequest(
                accountId, TransactionType.INCOME, new BigDecimal("100.00"),
                "Test", LocalDate.now(), null, null, null, null, null, null, null);

        assertThatThrownBy(() -> transactionService.createTransaction(request, userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createTransaction_appliesCategoryRule_whenNoCategory() {
        Category cat = new Category();
        ReflectionTestUtils.setField(cat, "id", UUID.randomUUID());
        cat.setName("Food");

        CategoryRule rule = new CategoryRule();
        rule.setPattern("restaurant");
        rule.setCategory(cat);
        rule.setPriority(0);
        rule.setActive(true);

        when(accountRepository.findByIdAndUserIdAndDeletedAtIsNull(accountId, userId))
                .thenReturn(Optional.of(account));
        when(categoryRuleRepository.findAllByUserIdAndIsActiveTrueOrderByPriorityAsc(userId))
                .thenReturn(List.of(rule));
        when(transactionRepository.save(any())).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(t, "createdAt", Instant.now());
            ReflectionTestUtils.setField(t, "updatedAt", Instant.now());
            return t;
        });

        CreateTransactionRequest request = new CreateTransactionRequest(
                accountId, TransactionType.EXPENSE, new BigDecimal("45.00"),
                "Italian Restaurant", LocalDate.now(), null, null, null, null, null, null, null);

        transactionService.createTransaction(request, userId);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getCategory()).isNotNull();
        assertThat(captor.getValue().getCategory().getName()).isEqualTo("Food");
    }

    // ── markAsPaid ────────────────────────────────────────────────────────────

    @Test
    void markAsPaid_fromPending_success() {
        Transaction tx = buildPendingTransaction();
        when(transactionRepository.findByIdAndUserId(tx.getId(), userId)).thenReturn(Optional.of(tx));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransactionDetailResponse response = transactionService.markAsPaid(
                tx.getId(), new MarkAsPaidRequest(null), userId);

        assertThat(response.status()).isEqualTo(TransactionStatus.PAID);
        assertThat(response.paymentDate()).isEqualTo(LocalDate.now());
    }

    @Test
    void markAsPaid_fromPaid_rejected() {
        Transaction tx = buildPaidTransaction();
        when(transactionRepository.findByIdAndUserId(tx.getId(), userId)).thenReturn(Optional.of(tx));

        assertThatThrownBy(() -> transactionService.markAsPaid(tx.getId(), new MarkAsPaidRequest(null), userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("PAID");
    }

    // ── cancelTransaction ─────────────────────────────────────────────────────

    @Test
    void cancelTransaction_fromPending_success() {
        Transaction tx = buildPendingTransaction();
        when(transactionRepository.findByIdAndUserId(tx.getId(), userId)).thenReturn(Optional.of(tx));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransactionDetailResponse response = transactionService.cancelTransaction(tx.getId(), userId);

        assertThat(response.status()).isEqualTo(TransactionStatus.CANCELLED);
        assertThat(response.cancelledAt()).isNotNull();
    }

    @Test
    void cancelTransaction_alreadyCancelled_rejected() {
        Transaction tx = buildPendingTransaction();
        tx.setStatus(TransactionStatus.CANCELLED);
        when(transactionRepository.findByIdAndUserId(tx.getId(), userId)).thenReturn(Optional.of(tx));

        assertThatThrownBy(() -> transactionService.cancelTransaction(tx.getId(), userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already cancelled");
    }

    // ── deleteTransaction ─────────────────────────────────────────────────────

    @Test
    void deleteTransaction_regular_success() {
        Transaction tx = buildPaidTransaction();
        when(transactionRepository.findByIdAndUserId(tx.getId(), userId)).thenReturn(Optional.of(tx));

        transactionService.deleteTransaction(tx.getId(), userId);

        verify(transactionRepository).delete(tx);
    }

    @Test
    void deleteTransaction_transferLeg_rejected() {
        Transaction tx = buildPaidTransaction();
        tx.setTransferGroupId(UUID.randomUUID());
        when(transactionRepository.findByIdAndUserId(tx.getId(), userId)).thenReturn(Optional.of(tx));

        assertThatThrownBy(() -> transactionService.deleteTransaction(tx.getId(), userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("transfer");

        verify(transactionRepository, never()).delete(any());
    }

    // ── listTransactions ──────────────────────────────────────────────────────

    @Test
    void listTransactions_returnsPaginatedResults() {
        Transaction tx = buildPaidTransaction();
        Page<Transaction> page = new PageImpl<>(List.of(tx));

        when(transactionRepository.findWithFilters(
                eq(userId), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(false),
                any())).thenReturn(page);

        Page<TransactionSummaryResponse> result = transactionService.listTransactions(
                TransactionFilterRequest.empty(), userId, PageRequest.of(0, 10));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).id()).isEqualTo(tx.getId());
    }

    // ── detectOverdue ─────────────────────────────────────────────────────────

    @Test
    void detectOverdue_callsRepository() {
        when(transactionRepository.markOverdueForUser(eq(userId), any(LocalDate.class))).thenReturn(3);

        int count = transactionService.detectOverdue(userId);

        assertThat(count).isEqualTo(3);
        verify(transactionRepository).markOverdueForUser(eq(userId), eq(LocalDate.now()));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Transaction buildPaidTransaction() {
        Transaction tx = new Transaction();
        ReflectionTestUtils.setField(tx, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(tx, "createdAt", Instant.now());
        ReflectionTestUtils.setField(tx, "updatedAt", Instant.now());
        tx.setUserId(userId);
        tx.setAccount(account);
        tx.setType(TransactionType.EXPENSE);
        tx.setStatus(TransactionStatus.PAID);
        tx.setAmount(new BigDecimal("100.00"));
        tx.setDescription("Test");
        tx.setCompetenceDate(LocalDate.now());
        tx.setPaymentDate(LocalDate.now());
        return tx;
    }

    private Transaction buildPendingTransaction() {
        Transaction tx = buildPaidTransaction();
        tx.setStatus(TransactionStatus.PENDING);
        tx.setPaymentDate(null);
        return tx;
    }
}
