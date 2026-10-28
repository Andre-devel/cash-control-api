package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.Account;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final AttachmentService attachmentService;

    @Override
    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request, UUID userId) {
        if (accountRepository.existsByUserIdAndNameAndDeletedAtIsNull(userId, request.name())) {
            throw new ConflictException("Já existe uma conta com o nome '" + request.name() + "'.");
        }

        Account account = new Account();
        account.setUserId(userId);
        account.setName(request.name());
        account.setType(request.type());
        account.setCurrencyCode(resolveCurrency(request.currencyCode()));
        account.setDescription(request.description());
        account.setSortOrder(request.sortOrder() != null ? request.sortOrder() : 0);
        account = accountRepository.save(account);

        BigDecimal initialBalance = request.initialBalance();
        if (initialBalance != null && initialBalance.compareTo(BigDecimal.ZERO) != 0) {
            Transaction seed = buildManualAdjustment(account, userId, initialBalance,
                    "Saldo inicial", LocalDate.now());
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

        Map<UUID, BigDecimal> balances = new HashMap<>();
        for (Object[] row : transactionRepository.sumPaidAmountByAccountForUser(userId)) {
            balances.put((UUID) row[0], (BigDecimal) row[1]);
        }
        return accounts.stream()
                .map(a -> toResponse(a, balances.getOrDefault(a.getId(), BigDecimal.ZERO)))
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
            throw new ConflictException("Já existe uma conta com o nome '" + request.name() + "'.");
        }

        account.setName(request.name());
        account.setType(request.type());
        // A moeda é fixa: o saldo é a soma dos lançamentos, todos gravados nela.
        if (request.currencyCode() != null && !request.currencyCode().equals(account.getCurrencyCode())) {
            throw new BusinessRuleException("A moeda da conta não pode ser alterada.");
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
            throw new BusinessRuleException("A conta já está arquivada.");
        }
        requireZeroBalance(id, userId, "arquivada");
        account.setArchivedAt(Instant.now());
        account = accountRepository.save(account);
        return toResponse(account, computeBalance(id, userId));
    }

    @Override
    @Transactional
    public AccountResponse unarchiveAccount(UUID id, UUID userId) {
        Account account = findActiveAccount(id, userId);
        if (account.getArchivedAt() == null) {
            throw new BusinessRuleException("A conta não está arquivada.");
        }
        account.setArchivedAt(null);
        account = accountRepository.save(account);
        return toResponse(account, computeBalance(id, userId));
    }

    @Override
    @Transactional
    public void deleteAccount(UUID id, UUID userId) {
        Account account = findActiveAccount(id, userId);

        // Ajustes (o saldo inicial entre eles) só mexem no saldo; qualquer outro lançamento
        // vivo é histórico que sumiria junto com a conta.
        boolean hasTransactions = transactionRepository
                .existsByAccount_IdAndUserIdAndStatusNotAndTypeNot(
                        id, userId, TransactionStatus.CANCELLED, TransactionType.MANUAL_ADJUSTMENT);
        if (hasTransactions) {
            throw new BusinessRuleException(
                    "A conta não pode ser excluída porque possui transações. Arquive-a em vez disso.");
        }
        requireZeroBalance(id, userId, "excluída");

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
                : "Ajuste manual";

        Transaction tx = buildManualAdjustment(account, userId, request.amount(), description, date);
        transactionRepository.save(tx);

        return toResponse(account, computeBalance(id, userId));
    }

    @Override
    @Transactional
    public void createTransfer(TransferRequest request, UUID userId) {
        if (request.sourceAccountId().equals(request.destinationAccountId())) {
            throw new BusinessRuleException("As contas de origem e destino devem ser diferentes.");
        }

        Account source = findActiveAccount(request.sourceAccountId(), userId);
        Account destination = findActiveAccount(request.destinationAccountId(), userId);
        LocalDate date = request.date() != null ? request.date() : LocalDate.now();

        transfer(source, destination, request.amount(), date, request.description());
    }

    /**
     * Sem {@code @Transactional} de propósito: entra na transação de quem chama, e uma regra
     * violada aqui não marca essa transação como rollback-only. O import de extrato depende
     * disso para reportar o erro na linha e seguir com as demais.
     */
    @Override
    public List<Transaction> transfer(Account source, Account destination, BigDecimal amount,
                                      LocalDate date, String description) {
        if (source.getId().equals(destination.getId())) {
            throw new BusinessRuleException("As contas de origem e destino devem ser diferentes.");
        }
        if (source.getArchivedAt() != null) {
            throw new BusinessRuleException("A conta de origem está arquivada e não pode ser usada em transferências.");
        }
        if (destination.getArchivedAt() != null) {
            throw new BusinessRuleException("A conta de destino está arquivada e não pode ser usada em transferências.");
        }
        if (!source.getCurrencyCode().equals(destination.getCurrencyCode())) {
            throw new BusinessRuleException("Transferências só são permitidas entre contas da mesma moeda.");
        }

        UUID transferGroupId = UUID.randomUUID();
        String legDescription = description != null && !description.isBlank() ? description : "Transferência";
        PaymentMethod paymentMethod = resolveDefaultPaymentMethod();

        // Source leg: negative amount represents debit from the account
        Transaction sourceLeg = new Transaction();
        sourceLeg.setUserId(source.getUserId());
        sourceLeg.setAccount(source);
        sourceLeg.setType(TransactionType.TRANSFER);
        sourceLeg.setStatus(TransactionStatus.PAID);
        sourceLeg.setAmount(amount.negate());
        sourceLeg.setDescription(legDescription);
        sourceLeg.setCompetenceDate(date);
        sourceLeg.setPaymentDate(date);
        sourceLeg.setPaymentMethod(paymentMethod);
        sourceLeg.setTransferGroupId(transferGroupId);

        // Destination leg: positive amount represents credit to the account
        Transaction destinationLeg = new Transaction();
        destinationLeg.setUserId(destination.getUserId());
        destinationLeg.setAccount(destination);
        destinationLeg.setType(TransactionType.TRANSFER);
        destinationLeg.setStatus(TransactionStatus.PAID);
        destinationLeg.setAmount(amount);
        destinationLeg.setDescription(legDescription);
        destinationLeg.setCompetenceDate(date);
        destinationLeg.setPaymentDate(date);
        destinationLeg.setPaymentMethod(paymentMethod);
        destinationLeg.setTransferGroupId(transferGroupId);

        transactionRepository.save(sourceLeg);
        transactionRepository.save(destinationLeg);
        return List.of(sourceLeg, destinationLeg);
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

        attachmentService.deleteAllForTransactions(legs.stream().map(Transaction::getId).toList());
        transactionRepository.deleteAll(legs);
    }

    /** Código ISO 4217 em maiúsculas; BRL quando omitido. */
    private static String resolveCurrency(String code) {
        if (code == null || code.isBlank()) {
            return "BRL";
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        try {
            return Currency.getInstance(normalized).getCurrencyCode();
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("Moeda inválida: '" + code + "'. Use um código ISO 4217, como BRL.");
        }
    }

    /**
     * Conta arquivada ou excluída sai do patrimônio; com saldo, o dinheiro sumiria dos totais.
     * O usuário zera antes com transferência ou ajuste.
     */
    private void requireZeroBalance(UUID id, UUID userId, String action) {
        if (computeBalance(id, userId).signum() != 0) {
            throw new BusinessRuleException("A conta tem saldo e não pode ser " + action
                    + ". Transfira o saldo para outra conta ou ajuste-o para zero antes.");
        }
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
        tx.setPaymentMethod(resolveDefaultPaymentMethod());
        return tx;
    }

    private PaymentMethod resolveDefaultPaymentMethod() {
        return paymentMethodRepository.findBySlug(PaymentMethodSlug.OTHER)
                .orElseThrow(() -> new ResourceNotFoundException("Payment method not found: " + PaymentMethodSlug.OTHER));
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
