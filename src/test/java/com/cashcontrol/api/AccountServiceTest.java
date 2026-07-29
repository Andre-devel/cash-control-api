package com.cashcontrol.api;

import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.PaymentMethod;
import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ConflictException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.EditAccountRequest;
import com.cashcontrol.api.dto.request.ManualAdjustmentRequest;
import com.cashcontrol.api.dto.request.TransferRequest;
import com.cashcontrol.api.dto.response.AccountResponse;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.PaymentMethodRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.AccountServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private PaymentMethodRepository paymentMethodRepository;
    @InjectMocks private AccountServiceImpl accountService;

    private UUID userId;
    private UUID accountId;
    private Account account;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        account = buildAccount(accountId, userId, "Test Account", AccountType.CHECKING);

        PaymentMethod other = new PaymentMethod();
        other.setSlug(PaymentMethodSlug.OTHER);
        other.setName("Other");
        lenient().when(paymentMethodRepository.findBySlug(PaymentMethodSlug.OTHER))
                .thenReturn(Optional.of(other));
    }

    // ── createAccount ─────────────────────────────────────────────────────────

    @Test
    void createAccount_success_noInitialBalance() {
        when(accountRepository.existsByUserIdAndNameAndDeletedAtIsNull(userId, "My Account")).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(a, "createdAt", Instant.now());
            ReflectionTestUtils.setField(a, "updatedAt", Instant.now());
            return a;
        });
        when(transactionRepository.sumPaidAmountByAccountIdAndUserId(any(), eq(userId)))
                .thenReturn(BigDecimal.ZERO);

        CreateAccountRequest request = new CreateAccountRequest("My Account", AccountType.CHECKING, "BRL", null, 0, null);
        AccountResponse response = accountService.createAccount(request, userId);

        assertThat(response.name()).isEqualTo("My Account");
        assertThat(response.type()).isEqualTo(AccountType.CHECKING);
        assertThat(response.balance()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void createAccount_withInitialBalance_seedsManualAdjustment() {
        when(accountRepository.existsByUserIdAndNameAndDeletedAtIsNull(userId, "Savings")).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            ReflectionTestUtils.setField(a, "id", accountId);
            ReflectionTestUtils.setField(a, "createdAt", Instant.now());
            ReflectionTestUtils.setField(a, "updatedAt", Instant.now());
            return a;
        });
        when(transactionRepository.save(any())).thenReturn(null);
        when(transactionRepository.sumPaidAmountByAccountIdAndUserId(any(), eq(userId)))
                .thenReturn(new BigDecimal("500.00"));

        CreateAccountRequest request = new CreateAccountRequest("Savings", AccountType.SAVINGS, "BRL", null, 0, new BigDecimal("500.00"));
        AccountResponse response = accountService.createAccount(request, userId);

        assertThat(response.balance()).isEqualByComparingTo(new BigDecimal("500.00"));

        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(txCaptor.capture());
        Transaction seed = txCaptor.getValue();
        assertThat(seed.getType()).isEqualTo(TransactionType.MANUAL_ADJUSTMENT);
        assertThat(seed.getStatus()).isEqualTo(TransactionStatus.PAID);
        assertThat(seed.getAmount()).isEqualByComparingTo(new BigDecimal("500.00"));
    }

    @Test
    void createAccount_duplicateName_throwsConflictException() {
        when(accountRepository.existsByUserIdAndNameAndDeletedAtIsNull(userId, "Existing")).thenReturn(true);

        CreateAccountRequest request = new CreateAccountRequest("Existing", AccountType.CHECKING, "BRL", null, 0, null);
        assertThatThrownBy(() -> accountService.createAccount(request, userId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Existing");
    }

    @Test
    void createAccount_zeroInitialBalance_doesNotSeedTransaction() {
        when(accountRepository.existsByUserIdAndNameAndDeletedAtIsNull(userId, "Empty")).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            ReflectionTestUtils.setField(a, "id", accountId);
            ReflectionTestUtils.setField(a, "createdAt", Instant.now());
            ReflectionTestUtils.setField(a, "updatedAt", Instant.now());
            return a;
        });
        when(transactionRepository.sumPaidAmountByAccountIdAndUserId(any(), eq(userId)))
                .thenReturn(BigDecimal.ZERO);

        CreateAccountRequest request = new CreateAccountRequest("Empty", AccountType.CHECKING, "BRL", null, 0, BigDecimal.ZERO);
        accountService.createAccount(request, userId);

        verify(transactionRepository, never()).save(any());
    }

    // ── listAccounts ──────────────────────────────────────────────────────────

    @Test
    void listAccounts_excludeArchived_returnsOnlyActive() {
        when(accountRepository.findAllByUserIdAndDeletedAtIsNullAndArchivedAtIsNullOrderBySortOrderAscCreatedAtAsc(userId))
                .thenReturn(List.of(account));
        when(transactionRepository.sumPaidAmountByAccountIdAndUserId(accountId, userId))
                .thenReturn(new BigDecimal("100.00"));

        List<AccountResponse> result = accountService.listAccounts(userId, false);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(accountId);
        assertThat(result.get(0).balance()).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void listAccounts_includeArchived_returnsAll() {
        Account archived = buildAccount(UUID.randomUUID(), userId, "Archived", AccountType.SAVINGS);
        archived.setArchivedAt(Instant.now());

        when(accountRepository.findAllByUserIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(userId))
                .thenReturn(List.of(account, archived));
        when(transactionRepository.sumPaidAmountByAccountIdAndUserId(any(), eq(userId)))
                .thenReturn(BigDecimal.ZERO);

        List<AccountResponse> result = accountService.listAccounts(userId, true);

        assertThat(result).hasSize(2);
    }

    // ── getAccount ────────────────────────────────────────────────────────────

    @Test
    void getAccount_found_returnsResponse() {
        when(accountRepository.findByIdAndUserIdAndDeletedAtIsNull(accountId, userId))
                .thenReturn(Optional.of(account));
        when(transactionRepository.sumPaidAmountByAccountIdAndUserId(accountId, userId))
                .thenReturn(new BigDecimal("250.00"));

        AccountResponse response = accountService.getAccount(accountId, userId);

        assertThat(response.id()).isEqualTo(accountId);
        assertThat(response.balance()).isEqualByComparingTo(new BigDecimal("250.00"));
    }

    @Test
    void getAccount_notFound_throwsResourceNotFoundException() {
        when(accountRepository.findByIdAndUserIdAndDeletedAtIsNull(accountId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getAccount(accountId, userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── editAccount ───────────────────────────────────────────────────────────

    @Test
    void editAccount_success() {
        when(accountRepository.findByIdAndUserIdAndDeletedAtIsNull(accountId, userId))
                .thenReturn(Optional.of(account));
        when(accountRepository.existsByUserIdAndNameAndDeletedAtIsNullAndIdNot(userId, "New Name", accountId))
                .thenReturn(false);
        when(accountRepository.save(account)).thenReturn(account);
        when(transactionRepository.sumPaidAmountByAccountIdAndUserId(accountId, userId))
                .thenReturn(BigDecimal.ZERO);

        EditAccountRequest request = new EditAccountRequest("New Name", AccountType.SAVINGS, "USD", "desc", 1);
        AccountResponse response = accountService.editAccount(accountId, request, userId);

        assertThat(account.getName()).isEqualTo("New Name");
        assertThat(account.getType()).isEqualTo(AccountType.SAVINGS);
        assertThat(account.getCurrencyCode()).isEqualTo("USD");
        assertThat(response).isNotNull();
    }

    @Test
    void editAccount_duplicateName_throwsConflictException() {
        when(accountRepository.findByIdAndUserIdAndDeletedAtIsNull(accountId, userId))
                .thenReturn(Optional.of(account));
        when(accountRepository.existsByUserIdAndNameAndDeletedAtIsNullAndIdNot(userId, "Other Account", accountId))
                .thenReturn(true);

        EditAccountRequest request = new EditAccountRequest("Other Account", AccountType.CHECKING, "BRL", null, 0);
        assertThatThrownBy(() -> accountService.editAccount(accountId, request, userId))
                .isInstanceOf(ConflictException.class);
    }

    // ── archiveAccount / unarchiveAccount ────────────────────────────────────

    @Test
    void archiveAccount_success() {
        when(accountRepository.findByIdAndUserIdAndDeletedAtIsNull(accountId, userId))
                .thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);
        when(transactionRepository.sumPaidAmountByAccountIdAndUserId(accountId, userId))
                .thenReturn(BigDecimal.ZERO);

        accountService.archiveAccount(accountId, userId);

        assertThat(account.getArchivedAt()).isNotNull();
    }

    @Test
    void archiveAccount_alreadyArchived_throwsBusinessRuleException() {
        account.setArchivedAt(Instant.now());
        when(accountRepository.findByIdAndUserIdAndDeletedAtIsNull(accountId, userId))
                .thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.archiveAccount(accountId, userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already archived");
    }

    @Test
    void unarchiveAccount_success() {
        account.setArchivedAt(Instant.now());
        when(accountRepository.findByIdAndUserIdAndDeletedAtIsNull(accountId, userId))
                .thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);
        when(transactionRepository.sumPaidAmountByAccountIdAndUserId(accountId, userId))
                .thenReturn(BigDecimal.ZERO);

        accountService.unarchiveAccount(accountId, userId);

        assertThat(account.getArchivedAt()).isNull();
    }

    @Test
    void unarchiveAccount_notArchived_throwsBusinessRuleException() {
        when(accountRepository.findByIdAndUserIdAndDeletedAtIsNull(accountId, userId))
                .thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.unarchiveAccount(accountId, userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not archived");
    }

    // ── deleteAccount ─────────────────────────────────────────────────────────

    @Test
    void deleteAccount_noTransactions_succeeds() {
        when(accountRepository.findByIdAndUserIdAndDeletedAtIsNull(accountId, userId))
                .thenReturn(Optional.of(account));
        when(transactionRepository.countByAccount_IdAndUserIdAndStatusNot(accountId, userId, TransactionStatus.CANCELLED))
                .thenReturn(0L);
        when(accountRepository.save(account)).thenReturn(account);

        accountService.deleteAccount(accountId, userId);

        assertThat(account.getDeletedAt()).isNotNull();
    }

    @Test
    void deleteAccount_onlySeedTransaction_succeeds() {
        when(accountRepository.findByIdAndUserIdAndDeletedAtIsNull(accountId, userId))
                .thenReturn(Optional.of(account));
        when(transactionRepository.countByAccount_IdAndUserIdAndStatusNot(accountId, userId, TransactionStatus.CANCELLED))
                .thenReturn(1L);
        when(accountRepository.save(account)).thenReturn(account);

        accountService.deleteAccount(accountId, userId);

        assertThat(account.getDeletedAt()).isNotNull();
    }

    @Test
    void deleteAccount_hasTransactions_throwsBusinessRuleException() {
        when(accountRepository.findByIdAndUserIdAndDeletedAtIsNull(accountId, userId))
                .thenReturn(Optional.of(account));
        when(transactionRepository.countByAccount_IdAndUserIdAndStatusNot(accountId, userId, TransactionStatus.CANCELLED))
                .thenReturn(5L);

        assertThatThrownBy(() -> accountService.deleteAccount(accountId, userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("existing transactions");
    }

    // ── manualAdjustment ─────────────────────────────────────────────────────

    @Test
    void manualAdjustment_createsTransaction() {
        when(accountRepository.findByIdAndUserIdAndDeletedAtIsNull(accountId, userId))
                .thenReturn(Optional.of(account));
        when(transactionRepository.save(any())).thenReturn(null);
        when(transactionRepository.sumPaidAmountByAccountIdAndUserId(accountId, userId))
                .thenReturn(new BigDecimal("50.00"));

        ManualAdjustmentRequest request = new ManualAdjustmentRequest(new BigDecimal("50.00"), "Adjustment", LocalDate.now());
        AccountResponse response = accountService.manualAdjustment(accountId, request, userId);

        assertThat(response.balance()).isEqualByComparingTo(new BigDecimal("50.00"));

        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(txCaptor.capture());
        assertThat(txCaptor.getValue().getType()).isEqualTo(TransactionType.MANUAL_ADJUSTMENT);
        assertThat(txCaptor.getValue().getAmount()).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    // ── createTransfer ────────────────────────────────────────────────────────

    @Test
    void createTransfer_success_createsTwoLinkedTransactions() {
        UUID sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        Account source = buildAccount(sourceId, userId, "Source", AccountType.CHECKING);
        Account destination = buildAccount(destinationId, userId, "Destination", AccountType.SAVINGS);

        when(accountRepository.findByIdAndUserIdAndDeletedAtIsNull(sourceId, userId))
                .thenReturn(Optional.of(source));
        when(accountRepository.findByIdAndUserIdAndDeletedAtIsNull(destinationId, userId))
                .thenReturn(Optional.of(destination));
        when(transactionRepository.save(any())).thenReturn(null);

        TransferRequest request = new TransferRequest(sourceId, destinationId, new BigDecimal("200.00"), "Test transfer", LocalDate.now());
        accountService.createTransfer(request, userId);

        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(2)).save(txCaptor.capture());

        List<Transaction> saved = txCaptor.getAllValues();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(saved.get(1).getType()).isEqualTo(TransactionType.TRANSFER);

        // Source leg: negative amount (debit), destination leg: positive amount (credit)
        Transaction sourceLeg = saved.get(0);
        Transaction destLeg = saved.get(1);
        assertThat(sourceLeg.getAmount()).isEqualByComparingTo(new BigDecimal("-200.00"));
        assertThat(destLeg.getAmount()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(sourceLeg.getTransferGroupId()).isNotNull();
        assertThat(sourceLeg.getTransferGroupId()).isEqualTo(destLeg.getTransferGroupId());
    }

    @Test
    void createTransfer_sameAccount_throwsBusinessRuleException() {
        TransferRequest request = new TransferRequest(accountId, accountId, new BigDecimal("100.00"), null, null);
        assertThatThrownBy(() -> accountService.createTransfer(request, userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("different");
    }

    @Test
    void createTransfer_archivedSourceAccount_throwsBusinessRuleException() {
        UUID destinationId = UUID.randomUUID();
        account.setArchivedAt(Instant.now());
        Account destination = buildAccount(destinationId, userId, "Destination", AccountType.SAVINGS);

        when(accountRepository.findByIdAndUserIdAndDeletedAtIsNull(accountId, userId))
                .thenReturn(Optional.of(account));
        when(accountRepository.findByIdAndUserIdAndDeletedAtIsNull(destinationId, userId))
                .thenReturn(Optional.of(destination));

        TransferRequest request = new TransferRequest(accountId, destinationId, new BigDecimal("100.00"), null, null);
        assertThatThrownBy(() -> accountService.createTransfer(request, userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("archived");
    }

    @Test
    void createTransfer_sourceNotFound_throwsResourceNotFoundException() {
        UUID sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        when(accountRepository.findByIdAndUserIdAndDeletedAtIsNull(sourceId, userId))
                .thenReturn(Optional.empty());

        TransferRequest request = new TransferRequest(sourceId, destinationId, new BigDecimal("100.00"), null, null);
        assertThatThrownBy(() -> accountService.createTransfer(request, userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── deleteTransfer ────────────────────────────────────────────────────────

    @Test
    void deleteTransfer_success_deletesBothLegs() {
        UUID groupId = UUID.randomUUID();
        Transaction leg1 = new Transaction();
        leg1.setUserId(userId);
        Transaction leg2 = new Transaction();
        leg2.setUserId(userId);

        when(transactionRepository.findAllByTransferGroupId(groupId)).thenReturn(List.of(leg1, leg2));

        accountService.deleteTransfer(groupId, userId);

        verify(transactionRepository).deleteAll(List.of(leg1, leg2));
    }

    @Test
    void deleteTransfer_notFound_throwsResourceNotFoundException() {
        UUID groupId = UUID.randomUUID();
        when(transactionRepository.findAllByTransferGroupId(groupId)).thenReturn(List.of());

        assertThatThrownBy(() -> accountService.deleteTransfer(groupId, userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteTransfer_belongsToOtherUser_throwsResourceNotFoundException() {
        UUID groupId = UUID.randomUUID();
        Transaction leg = new Transaction();
        leg.setUserId(UUID.randomUUID()); // different user

        when(transactionRepository.findAllByTransferGroupId(groupId)).thenReturn(List.of(leg));

        assertThatThrownBy(() -> accountService.deleteTransfer(groupId, userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Account buildAccount(UUID id, UUID userId, String name, AccountType type) {
        Account a = new Account();
        ReflectionTestUtils.setField(a, "id", id);
        a.setUserId(userId);
        a.setName(name);
        a.setType(type);
        a.setCurrencyCode("BRL");
        ReflectionTestUtils.setField(a, "createdAt", Instant.now());
        ReflectionTestUtils.setField(a, "updatedAt", Instant.now());
        return a;
    }
}
