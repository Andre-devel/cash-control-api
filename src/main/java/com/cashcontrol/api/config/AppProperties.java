package com.cashcontrol.api.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(prefix = "app")
@Validated
@Getter
@Setter
public class AppProperties {

    @NotBlank
    private String baseUrl = "http://localhost:8080";

    @NotBlank
    private String frontendBaseUrl = "http://localhost:5173";

    @NotBlank
    private String oauth2SuccessRedirectUrl = "http://localhost:3000/auth/oauth2/callback";

    @NotBlank
    private String oauth2FailureRedirectUrl = "http://localhost:3000/auth/oauth2/error";

    @Valid
    private final Jwt jwt = new Jwt();

    @Valid
    private final Cors cors = new Cors();

    @Valid
    private final Security security = new Security();

    @Valid
    private final Retention retention = new Retention();

    @Valid
    private final Storage storage = new Storage();

    @Valid
    private final Attachments attachments = new Attachments();

    @Valid
    private final StatementImport statementImport = new StatementImport();

    @Valid
    private final InvoiceImport invoiceImport = new InvoiceImport();

    @Valid
    private final ReceiptImport receiptImport = new ReceiptImport();

    @Valid
    private final Ocr ocr = new Ocr();

    @Valid
    private final Dashboard dashboard = new Dashboard();

    @Valid
    private final Mail mail = new Mail();

    @Getter
    @Setter
    public static class Jwt {

        @NotBlank
        private String secret;

        @Positive
        private int expirationMinutes = 15;

        @Positive
        private int refreshExpirationDays = 7;

        /**
         * Only turned off for local HTTP development — a refresh cookie without the
         * Secure attribute travels in clear text.
         */
        private boolean refreshCookieSecure = true;
    }

    @Getter
    @Setter
    public static class Cors {
        private String allowedOrigins = "http://localhost:3000";
    }

    @Getter
    @Setter
    public static class Security {

        @Positive
        private int maxFailedAttempts = 5;

        @Positive
        private int lockoutDurationMinutes = 15;

        @Positive
        private int passwordResetExpiryMinutes = 60;

        @Positive
        private int emailVerificationExpiryHours = 24;

        @Positive
        private int rateLimitRequestsPerMinute = 20;

        @Positive
        private int rateLimitWindowSeconds = 60;
    }

    @Getter
    @Setter
    public static class Retention {

        @Positive
        private int passwordResetDays = 30;

        @Positive
        private int verificationTokenDays = 7;

        @Positive
        private int refreshTokenDays = 30;
    }

    /** Onde os anexos ficam gravados. Liga em {@code app.storage.*}. */
    @Getter
    @Setter
    public static class Storage {

        /**
         * O default só serve para o dev local. Em produção precisa apontar para um volume
         * montado: {@code java.io.tmpdir} vive na camada de escrita do container e todo
         * anexo se perde no rebuild seguinte.
         */
        @NotBlank
        private String localPath = System.getProperty("java.io.tmpdir") + "/cash-control-attachments";
    }

    @Getter
    @Setter
    public static class Attachments {

        @Positive
        private int maxFileSizeMb = 10;

        @Positive
        private int maxPerTransaction = 5;

        private String allowedTypes = "pdf,png,jpg,jpeg";
    }

    /** Importação de extrato bancário. Liga em {@code app.statement-import.*}. */
    @Getter
    @Setter
    public static class StatementImport {

        @Positive
        private int maxFileSizeMb = 5;

        /**
         * Teto de lançamentos por arquivo. Um extrato de dois anos tem ~700 linhas;
         * o limite existe para um upload absurdo não virar uma transação gigante.
         */
        @Positive
        private int maxRows = 5000;
    }

    /** Importação de fatura de cartão em PDF. Liga em {@code app.invoice-import.*}. */
    @Getter
    @Setter
    public static class InvoiceImport {

        /**
         * Sem teto de linhas equivalente ao do extrato: uma fatura raramente passa de
         * duas dezenas de lançamentos, e o tamanho do arquivo já limita o abuso.
         */
        @Positive
        private int maxFileSizeMb = 5;
    }

    /** Leitura de comprovante de pagamento. Liga em {@code app.receipt-import.*}. */
    @Getter
    @Setter
    public static class ReceiptImport {

        /**
         * Alinhado ao teto de anexo, e não ao dos outros imports: o comprovante lido vira
         * anexo da transação no mesmo passo, e um teto menor aqui só adiaria a recusa.
         */
        @Positive
        private int maxFileSizeMb = 10;
    }

    /**
     * OCR de comprovante em imagem. Liga em {@code app.ocr.*}.
     *
     * <p>O binário do sistema é chamado por {@code ProcessBuilder} em vez de uma binding
     * JNA: a imagem de runtime é Alpine (musl), onde o Tess4J espera {@code libtesseract}
     * compilado para glibc. A CLI também evita duas armadilhas — o Tesseract não é
     * thread-safe, e uma chamada JNI fixaria a carrier thread, já que a aplicação roda com
     * virtual threads ligadas.
     */
    @Getter
    @Setter
    public static class Ocr {

        /**
         * Desligado, comprovante em imagem entra sem campos lidos em vez de estourar erro —
         * é o comportamento certo para um ambiente sem o binário instalado.
         */
        private boolean enabled = true;

        @NotBlank
        private String binary = "tesseract";

        @NotBlank
        private String language = "por";

        @Positive
        private int timeoutSeconds = 20;

        /**
         * Teto de OCR simultâneo. Os endpoints de importação não passam pelo
         * {@code RateLimitingFilter}, que cobre só os caminhos de auth, e o OCR é o
         * processamento mais caro da API — sem teto, um punhado de uploads paralelos
         * ocupa a CPU do VPS inteiro.
         */
        @Positive
        private int maxConcurrent = 2;
    }

    @Getter
    @Setter
    public static class Dashboard {

        @Positive
        private int upcomingBillsDays = 7;

        @Positive
        private int upcomingBillsMaxResults = 20;
    }

    @Getter
    @Setter
    public static class Mail {

        @NotBlank
        private String from = "noreply@example.com";
    }
}