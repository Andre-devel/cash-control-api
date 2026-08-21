package com.cashcontrol.api.service;

import com.cashcontrol.api.dto.request.UpdateInvoiceItemRequest;
import com.cashcontrol.api.dto.response.InvoiceResponse;
import com.cashcontrol.api.dto.response.InvoiceSummaryResponse;
import com.cashcontrol.api.dto.response.MerchantScopeResponse;
import com.cashcontrol.api.dto.response.UpdateInvoiceItemResponse;
import org.springframework.data.domain.Page;

import java.util.UUID;

/**
 * A tela de gerenciamento de faturas: listar as faturas de um cartão, abrir uma pelo id,
 * corrigir descrição/categoria de um item já lançado (com o mesmo efeito de memória de
 * estabelecimento que o import de fatura tem), e as duas formas de quitação — a simples
 * ({@link #settle}, sem transação) e sua reversão ({@link #reopen}).
 *
 * <p>Pagar de verdade (com transação na conta) continua em {@code CreditCardService.payInvoice}
 * — não é reimplementado aqui.
 */
public interface InvoiceManagementService {

    Page<InvoiceSummaryResponse> listInvoices(UUID cardId, UUID userId, int page, int size);

    InvoiceResponse getInvoiceById(UUID invoiceId, UUID userId, int page, int size);

    UpdateInvoiceItemResponse updateItem(UUID itemId, UpdateInvoiceItemRequest request, UUID userId);

    MerchantScopeResponse getMerchantScope(UUID itemId, UUID userId);

    InvoiceResponse settle(UUID invoiceId, UUID userId);

    InvoiceResponse reopen(UUID invoiceId, UUID userId);
}
