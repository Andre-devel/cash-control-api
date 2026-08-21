package com.cashcontrol.api.service.receipt;

import com.cashcontrol.api.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

/**
 * Comprovante em imagem — o caso mais comum de compartilhamento: Nubank e PicPay entregam
 * um print, não um PDF.
 *
 * <p>Chama o binário {@code tesseract} do sistema via {@link ProcessBuilder} em vez de uma
 * binding JNI (Tess4J). Dois motivos, os dois documentados em {@link AppProperties.Ocr}:
 * a imagem de runtime é Alpine/musl, onde Tess4J tropeça na dependência de
 * {@code libtesseract} para glibc; e {@link Process#waitFor()} não fixa carrier thread,
 * ao contrário de uma chamada nativa via JNI — importante porque a aplicação roda com
 * {@code spring.threads.virtual.enabled=true}.
 *
 * <p>Tesseract não é thread-safe por instância, mas aqui cada chamada é um processo do
 * sistema operacional próprio — não há estado compartilhado para proteger. O que precisa
 * de proteção é a CPU do VPS: o {@link Semaphore} limita quantos OCRs rodam ao mesmo tempo,
 * porque os endpoints de importação não passam pelo {@code RateLimitingFilter}.
 */
@Slf4j
@Component
public class OcrReceiptTextExtractor implements ReceiptTextExtractor {

    private static final Set<String> SUPPORTED_MIME_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");

    /** Print de banco já é um render digital limpo — upscale simples basta, sem OpenCV. */
    private static final int UPSCALE_FACTOR = 2;

    private final AppProperties.Ocr config;
    private final Semaphore concurrencyLimiter;

    public OcrReceiptTextExtractor(AppProperties appProperties) {
        this.config = appProperties.getOcr();
        this.concurrencyLimiter = new Semaphore(config.getMaxConcurrent());
    }

    @Override
    public boolean supports(String mimeType) {
        return config.isEnabled() && SUPPORTED_MIME_TYPES.contains(mimeType);
    }

    /**
     * @return o texto lido, ou string vazia quando o OCR falha, estoura o timeout, ou o
     *         semáforo não libera uma vaga a tempo. Comprovante em imagem sem leitura ainda
     *         vira anexo com os campos em branco — não é motivo para recusar o upload.
     */
    @Override
    public String extract(MultipartFile file) {
        if (!concurrencyLimiter.tryAcquire()) {
            log.warn("OCR rejeitado: limite de {} execuções simultâneas atingido", config.getMaxConcurrent());
            return "";
        }
        try {
            return runTesseract(preprocess(file));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Falha ao executar OCR no comprovante", e);
            return "";
        } finally {
            concurrencyLimiter.release();
        }
    }

    /** Escala de cinza + upscale — o mínimo que melhora a leitura de um print de tela. */
    private Path preprocess(MultipartFile file) throws IOException {
        BufferedImage original = ImageIO.read(file.getInputStream());
        if (original == null) {
            throw new IOException("Arquivo não reconhecido como imagem: " + file.getOriginalFilename());
        }

        int width = original.getWidth() * UPSCALE_FACTOR;
        int height = original.getHeight() * UPSCALE_FACTOR;
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        scaled.getGraphics().drawImage(
                original.getScaledInstance(width, height, java.awt.Image.SCALE_SMOOTH), 0, 0, null);

        Path tmp = Files.createTempFile("receipt-ocr-", ".png");
        ImageIO.write(scaled, "png", tmp.toFile());
        return tmp;
    }

    private String runTesseract(Path imagePath) throws IOException, InterruptedException {
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    config.getBinary(), imagePath.toString(), "stdout",
                    "-l", config.getLanguage(), "--psm", "6");
            // Descartado, não misturado ao stdout: juntar os dois arriscaria colar o
            // banner de versão do tesseract no meio do texto que os regexes esperam.
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = builder.start();

            String output;
            try (var out = process.getInputStream()) {
                output = new String(out.readAllBytes());
            }

            boolean finished = process.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("OCR excedeu o timeout de {}s e foi encerrado", config.getTimeoutSeconds());
                return "";
            }
            if (process.exitValue() != 0) {
                log.warn("tesseract encerrou com código {}", process.exitValue());
                return "";
            }
            return output;
        } finally {
            Files.deleteIfExists(imagePath);
        }
    }
}
