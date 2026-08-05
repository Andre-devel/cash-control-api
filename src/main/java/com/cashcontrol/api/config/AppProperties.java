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
    private final Attachments attachments = new Attachments();

    @Valid
    private final StatementImport statementImport = new StatementImport();

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