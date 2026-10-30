package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.InvoiceImportFormat;
import com.cashcontrol.api.dto.request.FaturaImportCommitRequest;
import com.cashcontrol.api.dto.request.FaturaImportDuplicateCheckRequest;
import com.cashcontrol.api.dto.response.BankBillResponse;
import com.cashcontrol.api.dto.response.FaturaImportDuplicateCheckResponse;
import com.cashcontrol.api.dto.response.FaturaImportPreviewResponse;
import com.cashcontrol.api.dto.response.FaturaImportResultResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
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

    /** Faturas fechadas do cartão do banco vinculado a este cartão, da mais recente para a mais antiga. */
    List<BankBillResponse> listBankBills(UUID creditCardId, UUID userId);

    /**
     * A mesma prévia, com a fatura {@code billId} buscada no banco (Pluggy) do cartão vinculado.
     * Toda seção sugere o próprio cartão vinculado, inclusive a do cartão virtual. A confirmação
     * é a de sempre, com {@code InvoiceImportFormat.OPEN_FINANCE}.
     */
    FaturaImportPreviewResponse previewFromBank(UUID creditCardId, String billId, UUID userId);

    FaturaImportResultResponse commit(FaturaImportCommitRequest request, UUID userId);

    /**
     * Refaz a marcação de duplicatas de uma seção do PDF contra um cartão escolhido
     * à mão, para quando a prévia não teve como sugerir o cartão (um cartão virtual,
     * cujos 4 dígitos não batem com nenhum cadastrado).
     *
     * <p>Mesma checagem da prévia, só que com o cartão vindo do cliente: sem ela, a
     * seção fica sem nenhuma linha marcada e o usuário só descobre o que já entrou
     * quando a confirmação as ignora.
     */
    FaturaImportDuplicateCheckResponse checkDuplicates(FaturaImportDuplicateCheckRequest request, UUID userId);
}
