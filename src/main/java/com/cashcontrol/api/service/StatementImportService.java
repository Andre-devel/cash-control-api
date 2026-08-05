package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.StatementFormat;
import com.cashcontrol.api.dto.request.ImportCommitRequest;
import com.cashcontrol.api.dto.response.ImportPreviewResponse;
import com.cashcontrol.api.dto.response.ImportResultResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Importação de extrato bancário em duas etapas.
 *
 * <p>A prévia não persiste nada e não guarda estado no servidor: o cliente
 * recebe as linhas já classificadas e devolve, na confirmação, as que o usuário
 * aprovou. Gravar 700 transações que o usuário nunca viu é pior do que uma ida
 * e volta a mais.
 */
public interface StatementImportService {

    ImportPreviewResponse preview(MultipartFile file, StatementFormat format, UUID accountId, UUID userId);

    ImportResultResponse commit(ImportCommitRequest request, UUID userId);
}
