package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.InvoiceImportFormat;
import com.cashcontrol.api.dto.request.FaturaImportCommitRequest;
import com.cashcontrol.api.dto.response.FaturaImportPreviewResponse;
import com.cashcontrol.api.dto.response.FaturaImportResultResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Importação de fatura de cartão de crédito em duas etapas.
 *
 * <p>Como no extrato, a prévia não persiste nada e não guarda estado no servidor:
 * o cliente recebe os lançamentos agrupados por seção de cartão e devolve, na
 * confirmação, os que o usuário aprovou — já com o cartão cadastrado escolhido
 * para cada grupo.
 *
 * <p>A diferença estrutural em relação ao extrato é essa: um PDF de fatura cobre
 * o titular e os adicionais, então não existe "o cartão da importação" a ser
 * escolhido antes do upload. Ele só é conhecido depois da leitura.
 */
public interface FaturaImportService {

    FaturaImportPreviewResponse preview(MultipartFile file, InvoiceImportFormat format, UUID userId);

    FaturaImportResultResponse commit(FaturaImportCommitRequest request, UUID userId);
}
