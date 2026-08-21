package com.cashcontrol.api.service.receipt;

import org.springframework.web.multipart.MultipartFile;

/**
 * Tira o texto de um comprovante, seja qual for o formato em que ele chegou.
 *
 * <p>Implementações são {@code @Component} e o serviço as resolve por
 * {@link #supports(String)} — mesma ideia de {@code FaturaParser}, mas o critério é o tipo
 * do arquivo e não um formato escolhido pelo usuário: quem compartilha um comprovante do
 * app do banco não sabe (nem deveria precisar saber) se o que saiu foi PDF ou imagem.
 *
 * <p>A extração é separada do parsing de propósito, como em
 * {@code InterFaturaPdfParser.extractText}/{@code parseText}: é o que permite testar as
 * heurísticas de leitura contra texto sintético, sem depender de binário de PDF nem de ter
 * o Tesseract instalado na máquina que roda os testes.
 */
public interface ReceiptTextExtractor {

    boolean supports(String mimeType);

    /**
     * @return o texto do comprovante, ou string vazia quando o formato é suportado mas nada
     *         pôde ser lido. Vazio não é erro: o comprovante ainda vira anexo, e o usuário
     *         preenche a mão
     * @throws com.cashcontrol.api.domain.exception.BusinessRuleException quando o arquivo
     *         não é sequer legível como o formato que diz ser
     */
    String extract(MultipartFile file);
}
