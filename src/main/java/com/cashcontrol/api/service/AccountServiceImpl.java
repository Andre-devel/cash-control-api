package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.Account;
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
import com.cashcontrol.api.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    @Override
    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request, UUID userId) {
        if (accountRepository.existsByUserIdAndNameAndDeletedAtIsNull(userId, request.name())) {
            throw new ConflictException("An account with the name '" + request.name() + "' already exists.");
        }

        Account account = new Account();
        account.setUserId(userId);
        account.setName(request.name());
        account.setType(request.type());
        account.setCurrencyCode(request.currencyCode() != null ? request.currencyCode() : "BRL");
        account.setDescription(request.description());
        account.setSortOrder(request.sortOrder() != null ? request.sortOrder() : 0);
        account = accountRepository.save(account);

        BigDecimal initialBalance = request.initialBalance();
        if (initialBalance != null && initialBalance.compareTo(BigDecimal.ZERO) != 0) {
            Transaction seed = buildManualAdjustment(account, userId, initialBalance,
                    "Initial balance", LocalDate.now());
            transactionRepository.save(seed);
        }

        BigDecimal balance = computeBalance(account.getId(), userId);
        return toResponse(account, balance);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountResponse> listAccounts(UUID userId, boolean includeArchived) {
        List<Account> accounts = includeArchived
                ? accountRepository.findAllByUserIdAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(userId)
                : accountRepository.findAllByUserIdAndDeletedAtIsNullAndArchivedAtIsNullOrderBySortOrderAscCreatedAtAsc(userId);

        return accounts.stream()
                .map(a -> toResponse(a, computeBalance(a.getId(), userId)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AccountResponse getAccount(UUID id, UUID userId) {
        Account account = findActiveAccount(id, userId);
        return toResponse(account, computeBalance(id, userId));
    }

    @Override
    @Transactional
    public AccountResponse editAccount(UUID id, EditAccountRequest request, UUID userId) {
        Account account = findActiveAccount(id, userId);

        if (!account.getName().equals(request.name()) &&
                accountRepository.existsByUserIdAndNameAndDeletedAtIsNullAndIdNot(userId, request.name(), id)) {
            throw new ConflictException("An account with the name '" + request.name() + "' already exists.");
        }

        account.setName(request.name());
        account.setType(request.type());
        if (request.currencyCode() != null) {
            account.setCurrencyCode(request.currencyCode());
        }
        account.setDescription(request.description());
        if (request.sortOrder() != null) {
            account.setSortOrder(request.sortOrder());
        }
        account = accountRepository.save(account);
        return toResponse(account, computeBalance(id, userId));
    }

    @Override
    @Transactional
    public AccountResponse archiveAccount(UUID id, UUID userId) {
        Account account = findActiveAccount(id, userId);
        if (account.getArchivedAt() != null) {
            throw new BusinessRuleException("Account is already archived.");
        }
        account.setArchivedAt(Instant.now());
        account = accountRepository.save(account);
        return toResponse(account, computeBalance(id, userId));
    }

    @Override
    @Transactional
    public AccountResponse unarchiveAccount(UUID id, UUID userId) {
        Account account = findActiveAccount(id, userId);
        if (account.getArchivedAt() == null) {
            throw new BusinessRuleException("Account is not archived.");
        }
        account.setArchivedAt(null);
        account = accountRepository.save(account);
        return toResponse(account, computeBalance(id, userId));
    }

    @Override
    @Transactional
    public void deleteAccount(UUID id, UUID userId) {
        Account account = findActiveAccount(id, userId);

        long nonCancelledCount = transactionRepository
                .countByAccount_IdAndUserIdAndStatusNot(id, userId, TransactionStatus.CANCELLED);
        if (nonCancelledCount > 1) {
            throw new BusinessRuleException(
                    "Account cannot be deleted because it has existing transactions. Archive it instead.");
        }

        account.setDeletedAt(Instant.now());
        accountRepository.save(account);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal computeBalance(UUID accountId, UUID userId) {
        BigDecimal balance = transactionRepository.sumPaidAmountByAccountIdAndUserId(accountId, userId);
        return balance != null ? balance : BigDecimal.ZERO;
    }

    @Override
    @Transactional
    public AccountResponse manualAdjustment(UUID id, ManualAdjustmentRequest request, UUID userId) {
        Account account = findActiveAccount(id, userId);

        LocalDate date = request.date() != null ? request.date() : LocalDate.now();
        String description = request.description() != null && !request.description().isBlank()
                ? request.description()
                : "Manual adjustment";

        Transaction tx = buildManualAdjustment(account, userId, request.amount(), description, date);
        transactionRepository.save(tx);

        return toResponse(account, computeBalance(id, userId));
    }

    @Override
    @Transactional
    public void createTransfer(TransferRequest request, UUID userId) {
        if (request.sourceAccountId().equals(request.destinationAccountId())) {
            throw new BusinessRuleException("Source and destination accounts must be different.");
        }

        Account source = findActiveAccount(request.sourceAccountId(), userId);
        Account destination = findActiveAccount(request.destinationAccountId(), userId);

        if (source.getArchivedAt() != null) {
            throw new BusinessRuleException("Source account is archived and cannot be used for transfers.");
        }
        if (destination.getArchivedAt() != null) {
            throw new BusinessRuleException("Destination account is archived and cannot be used for transfers.");
        }

        UUID transferGroupId = UUID.randomUUID();
        LocalDate date = request.date() != null ? request.date() : LocalDate.now();
        String description = request.description() != null && !request.description().isBlank()
                ? request.description()
                : "Transfer";

        // Source leg: negative amount represents debit from the account
        Transaction sourceLeg = new Transaction();
        sourceLeg.setUserId(userId);
        sourceLeg.setAccount(source);
        sourceLeg.setType(TransactionType.TRANSFER);
        sourceLeg.setStatus(TransactionStatus.PAID);
        sourceLeg.setAmount(request.amount().negate());
        sourceLeg.setDescription(description);
        sourceLeg.setCompetenceDate(date);
        sourceLeg.setPaymentDate(date);
        sourceLeg.setTransferGroupId(transferGroupId);

        // Destination leg: positive amount represents credit to the account
        Transaction destinationLeg = new Transaction();
        destinationLeg.setUserId(userId);
        destinationLeg.setAccount(destination);
        destinationLeg.setType(TransactionType.TRANSFER);
        destinationLeg.setStatus(TransactionStatus.PAID);
        destinationLeg.setAmount(request.amount());
        destinationLeg.setDescription(description);
        destinationLeg.setCompetenceDate(date);
        destinationLeg.setPaymentDate(date);
        destinationLeg.setTransferGroupId(transferGroupId);

        transactionRepository.save(sourceLeg);
        transactionRepository.save(destinationLeg);
    }

    @Override
    @Transactional
    public void deleteTransfer(UUID transferGroupId, UUID userId) {
        List<Transaction> legs = transactionRepository.findAllByTransferGroupId(transferGroupId);

        if (legs.isEmpty()) {
            throw new ResourceNotFoundException("Transfer not found: " + transferGroupId);
        }

        for (Transaction leg : legs) {
            if (!userId.equals(leg.getUserId())) {
                throw new ResourceNotFoundException("Transfer not found: " + transferGroupId);
            }
        }

        transactionRepository.deleteAll(legs);
    }

    private Account findActiveAccount(UUID id, UUID userId) {
        return accountRepository.findByIdAndUserIdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + id));
    }

    private Transaction buildManualAdjustment(Account account, UUID userId, BigDecimal amount,
                                               String description, LocalDate date) {
        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setAccount(account);
        tx.setType(TransactionType.MANUAL_ADJUSTMENT);
        tx.setStatus(TransactionStatus.PAID);
        tx.setAmount(amount);
        tx.setDescription(description);
        tx.setCompetenceDate(date);
        tx.setPaymentDate(date);
        return tx;
    }

    private AccountResponse toResponse(Account account, BigDecimal balance) {
        return new AccountResponse(
                account.getId(),
                account.getName(),
                account.getType(),
                account.getCurrencyCode(),
                account.getDescription(),
                account.getSortOrder(),
                balance,
                account.getArchivedAt(),
                account.getCreatedAt(),
                account.getUpdatedAt()
        );
    }
}
