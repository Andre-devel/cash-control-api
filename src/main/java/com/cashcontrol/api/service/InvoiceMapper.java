package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.Invoice;
import com.cashcontrol.api.domain.entity.InvoiceItem;
import com.cashcontrol.api.dto.response.InvoiceItemResponse;
import com.cashcontrol.api.dto.response.InvoiceResponse;
import org.springframework.data.domain.Page;

import java.util.UUID;

/**
 * Monta {@link InvoiceResponse} e {@link InvoiceItemResponse} a partir das entidades.
 *
 * <p>Compartilhado entre {@code CreditCardServiceImpl} (a fatura de um cartão/mês) e
 * {@code InvoiceManagementServiceImpl} (a fatura por id, na tela de gerenciamento) — as duas
 * expõem a mesma forma de resposta, e mantê-la em um só lugar evita que uma delas fique
 * para trás quando um campo novo é adicionado.
 */
public final class InvoiceMapper {

    private InvoiceMapper() {}

    public static InvoiceResponse toInvoiceResponse(
            Invoice invoice, UUID cardId, int page, int size, Page<InvoiceItem> itemsPage) {
        return new InvoiceResponse(
                invoice.getId(),
                cardId,
                invoice.getReferenceMonth(),
                invoice.getStatus(),
                invoice.getClosingDate(),
                invoice.getDueDate(),
                invoice.getTotalAmount(),
                invoice.getPaidAmount(),
                invoice.isPaidWithoutTransaction(),
                page,
                size,
                itemsPage.getTotalElements(),
                itemsPage.getContent().stream().map(InvoiceMapper::toItemResponse).toList(),
                invoice.getCreatedAt(),
                invoice.getUpdatedAt()
        );
    }

    public static InvoiceItemResponse toItemResponse(InvoiceItem item) {
        return new InvoiceItemResponse(
                item.getId(),
                item.getDescription(),
                item.getOriginalDescription(),
                item.getExternalRef() != null,
                item.getAmount(),
                item.getCompetenceDate(),
                item.getCategory() != null ? item.getCategory().getId() : null,
                item.getCategory() != null ? item.getCategory().getName() : null,
                item.getSubcategory() != null ? item.getSubcategory().getId() : null,
                item.getSubcategory() != null ? item.getSubcategory().getName() : null,
                item.getNotes(),
                item.isRevolving(),
                item.getInstallmentNumber(),
                item.getTotalInstallments(),
                item.getTransaction() != null ? item.getTransaction().getId() : null,
                item.getCancelledAt(),
                item.getCreatedAt(),
                item.getUpdatedAt()
        );
    }
}
