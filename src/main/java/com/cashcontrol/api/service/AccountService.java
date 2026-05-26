package com.cashcontrol.api.service;

import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.EditAccountRequest;
import com.cashcontrol.api.dto.request.ManualAdjustmentRequest;
import com.cashcontrol.api.dto.request.TransferRequest;
import com.cashcontrol.api.dto.response.AccountResponse;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface AccountService {

    AccountResponse createAccount(CreateAccountRequest request, UUID userId);

    List<AccountResponse> listAccounts(UUID userId, boolean includeArchived);

    AccountResponse getAccount(UUID id, UUID userId);

    AccountResponse editAccount(UUID id, EditAccountRequest request, UUID userId);

    AccountResponse archiveAccount(UUID id, UUID userId);

    AccountResponse unarchiveAccount(UUID id, UUID userId);

    void deleteAccount(UUID id, UUID userId);

    BigDecimal computeBalance(UUID accountId, UUID userId);

    AccountResponse manualAdjustment(UUID id, ManualAdjustmentRequest request, UUID userId);

    void createTransfer(TransferRequest request, UUID userId);

    void deleteTransfer(UUID transferGroupId, UUID userId);
}
