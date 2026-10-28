package com.cashcontrol.api.service;

import com.cashcontrol.api.dto.request.ApplyReconciliationRequest;
import com.cashcontrol.api.dto.response.ApplyReconciliationResult;
import com.cashcontrol.api.dto.response.ImportReconciliationResponse;

import java.util.UUID;

/**
 * Corrige lançamentos importados antes de o import reconhecer pagamento de fatura,
 * aplicação/resgate e parcelas de outro mês. Mostra antes o que vai mudar; aplicar é
 * idempotente.
 */
public interface ImportReconciliationService {

    ImportReconciliationResponse preview(UUID userId);

    ApplyReconciliationResult apply(ApplyReconciliationRequest request, UUID userId);
}
