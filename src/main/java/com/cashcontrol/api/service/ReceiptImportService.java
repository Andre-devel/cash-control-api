package com.cashcontrol.api.service;

import com.cashcontrol.api.dto.request.ReceiptCommitRequest;
import com.cashcontrol.api.dto.response.ReceiptPreviewResponse;
import com.cashcontrol.api.dto.response.TransactionDetailResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

public interface ReceiptImportService {

    /**
     * @param accountId opcional: no fluxo de compartilhamento a conta ainda não foi
     *                  escolhida quando o comprovante chega. Sem ela, a checagem de
     *                  duplicata fica pendente para o commit, que sempre a exige.
     */
    ReceiptPreviewResponse preview(MultipartFile file, UUID accountId, UUID userId);

    TransactionDetailResponse commit(ReceiptCommitRequest request, MultipartFile file, UUID userId);
}
