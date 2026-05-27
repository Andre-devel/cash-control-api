package com.cashcontrol.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Cash Control API")
                        .description("""
                                Production-ready personal finance management REST API built with Spring Boot 4 and Java 25.

                                ## Core Capabilities

                                - **Account & Wallet Management** — Multiple account types with balance tracking, archiving, and transfer support
                                - **Transaction Lifecycle** — Income, expense, transfer, refund, and manual adjustment with full status management
                                - **Installment Tracking** — Recurring and installment payment series with individual and series-wide editing
                                - **Category Management** — Hierarchical categories with color, icon, auto-suggestion, and category rules
                                - **Credit Card Billing** — Card limits, billing cycles, invoice tracking, and partial payment with revolving credit
                                - **Dashboard & Reporting** — Aggregated views, cash flow charts, and configurable widgets

                                ## Authentication

                                All financial endpoints require a valid JWT access token. Obtain a token via `POST /api/v1/auth/login`, \
                                then include it in every request as:

                                ```
                                Authorization: Bearer <token>
                                ```

                                Access tokens are short-lived. When a token expires (HTTP 401), re-authenticate explicitly. \
                                No refresh token mechanism is provided — this is a stateless, re-authentication-on-expiry architecture.

                                ## User Isolation

                                All financial data is strictly scoped to the authenticated user. \
                                Every repository query includes a `user_id` predicate derived from the JWT subject claim. \
                                Cross-user data access is architecturally impossible.

                                ## Monetary Precision

                                All monetary values use `BigDecimal` internally and are serialized as decimal strings in API responses \
                                (e.g., `"150.75"`) to prevent client-side floating-point loss.

                                ## Error Responses

                                All non-2xx responses return a standardized error envelope:
                                ```json
                                {
                                  "errorCode": "RESOURCE_NOT_FOUND",
                                  "message": "Account not found.",
                                  "correlationId": "550e8400-e29b-41d4-a716-446655440000",
                                  "timestamp": "2026-05-27T10:00:00Z"
                                }
                                ```
                                """)
                        .version("v1")
                        .contact(new Contact()
                                .name("Cash Control Team")
                                .email("api@cashcontrol.io"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .name("bearerAuth")
                                .description("JWT access token. Obtain via `POST /api/v1/auth/login`. "
                                        + "Include as `Authorization: Bearer <token>` on all protected requests.")));
    }
}
