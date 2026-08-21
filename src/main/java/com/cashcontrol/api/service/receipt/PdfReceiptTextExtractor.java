package com.cashcontrol.api.service.receipt;

import com.cashcontrol.api.domain.exception.BusinessRuleException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * Comprovante em PDF — o formato que Inter, Itaú e Bradesco entregam no "compartilhar".
 *
 * <p>Nenhuma dependência nova: o PDFBox já entrou no projeto para a importação de fatura.
 *
 * <p>Um PDF <em>escaneado</em> (página que é só uma imagem) sai daqui vazio, e não com erro:
 * é indistinguível de um PDF legítimo sem camada de texto, e recusá-lo tiraria do usuário o
 * anexo junto com os campos.
 */
@Component
public class PdfReceiptTextExtractor implements ReceiptTextExtractor {

    @Override
    public boolean supports(String mimeType) {
        return "application/pdf".equals(mimeType);
    }

    @Override
    public String extract(MultipartFile file) {
        try (InputStream in = file.getInputStream();
             PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(in))) {
            PDFTextStripper stripper = new PDFTextStripper();
            // Comprovante é diagramado em duas colunas (rótulo à esquerda, valor à direita).
            // Sem ordenar por posição, o PDFBox devolve o texto na ordem do content stream e
            // rótulo e valor se desencontram — o mesmo motivo pelo qual o parser da fatura liga isto.
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        } catch (IOException e) {
            throw new BusinessRuleException(
                    "Não foi possível ler o arquivo como PDF. Compartilhe o comprovante como o banco o gerou.");
        }
    }
}
