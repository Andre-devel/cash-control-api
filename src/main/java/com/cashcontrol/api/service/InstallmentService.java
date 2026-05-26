package com.cashcontrol.api.service;

import com.cashcontrol.api.dto.request.AdvanceInstallmentRequest;
import com.cashcontrol.api.dto.request.CreateInstallmentRequest;
import com.cashcontrol.api.dto.request.EarlySettlementRequest;
import com.cashcontrol.api.dto.request.EditInstallmentRequest;
import com.cashcontrol.api.dto.request.EditSeriesRequest;
import com.cashcontrol.api.dto.response.EarlySettlementResponse;
import com.cashcontrol.api.dto.response.EditSeriesResult;
import com.cashcontrol.api.dto.response.InstallmentSeriesDetailResponse;
import com.cashcontrol.api.dto.response.TransactionDetailResponse;

import java.util.List;
import java.util.UUID;

public interface InstallmentService {

    InstallmentSeriesDetailResponse createInstallmentSeries(CreateInstallmentRequest request, UUID userId);

    EditSeriesResult editSeries(UUID seriesId, EditSeriesRequest request, UUID userId);

    TransactionDetailResponse editInstallment(UUID transactionId, EditInstallmentRequest request, UUID userId);

    EarlySettlementResponse earlySettlement(UUID seriesId, EarlySettlementRequest request, UUID userId);

    List<TransactionDetailResponse> advanceInstallments(AdvanceInstallmentRequest request, UUID userId);
}
