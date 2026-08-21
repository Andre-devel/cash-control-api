package com.cashcontrol.api.dto.response;

public record UpdateInvoiceItemResponse(
        InvoiceItemResponse item,
        int updatedRelatedItems
) {}
