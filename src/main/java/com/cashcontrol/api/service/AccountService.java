package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.AccountScope;
import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.EditAccountRequest;
import com.cashcontrol.api.dto.request.ManualAdjustmentRequest;
import com.cashcontrol.api.dto.request.TransferRequest;
import com.cashcontrol.api.dto.response.AccountReconciliationResponse;
import com.cashcontrol.api.dto.response.AccountResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AccountService {

    AccountResponse createAccount(CreateAccountRequest request, UUID userId);

    /** Contas só do escopo; {@code scope} nulo traz todas. */
    List<AccountResponse> listAccounts(UUID userId, boolean includeArchived, AccountScope scope);

    AccountResponse getAccount(UUID id, UUID userId);

    AccountResponse editAccount(UUID id, EditAccountRequest request, UUID userId);

    AccountResponse archiveAccount(UUID id, UUID userId);

    AccountResponse unarchiveAccount(UUID id, UUID userId);

    void deleteAccount(UUID id, UUID userId);

    BigDecimal computeBalance(UUID accountId, UUID userId);

    AccountResponse manualAdjustment(UUID id, ManualAdjustmentRequest request, UUID userId);

    void createTransfer(TransferRequest request, UUID userId);

    /**
     * As duas pernas de uma transferência entre contas já carregadas e do mesmo usuário.
     * Devolve [origem, destino], já gravadas.
     */
    List<Transaction> transfer(Account source, Account destination, BigDecimal amount,
                               LocalDate date, String description);

    void deleteTransfer(UUID transferGroupId, UUID userId);

    /**
     * Leva o saldo da conta a {@code target} com um único ajuste, sempre o mesmo lançamento
     * ({@link Account#getSyncAdjustmentId()}), reescrito a cada chamada e apagado quando a
     * diferença some. Entra na transação de quem chama.
     */
    void reconcileBalance(Account account, BigDecimal target, LocalDate date, String description);

    /** Saldo do app ao lado do saldo lido do banco, para a conta vinculada a uma conexão. */
    AccountReconciliationResponse getReconciliation(UUID id, UUID userId);

    /**
     * Leva o saldo do app ao saldo lido do banco por {@link #reconcileBalance}: um único ajuste
     * flutuante por conta, reescrito a cada conciliação.
     *
     * @throws com.cashcontrol.api.domain.exception.BusinessRuleException sem saldo do banco
     */
    AccountReconciliationResponse reconcileWithBank(UUID id, UUID userId);
}
