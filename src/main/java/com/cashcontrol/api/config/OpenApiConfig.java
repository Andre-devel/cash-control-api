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
                        .title("Java Auth Template API")
                        .description("""
                                Production-ready authentication and authorization REST API built with Spring Boot and stateless JWT.

                                ## Authentication Flows

                                **JWT Bearer**: Obtain a token via `POST /api/v1/auth/login`, then include it in the \
                                `Authorization: Bearer <token>` header on protected requests.

                                **OAuth2 Google**: Redirect-based flow. Initiate via `/oauth2/authorization/google`. \
                                After successful authentication, the JWT access token is returned in the redirect URL parameter.

                                **Token Expiry**: Access tokens are short-lived (5–15 minutes). When a token expires, \
                                the client receives HTTP 401 and must re-authenticate via `POST /api/v1/auth/login`. \
                                No refresh token mechanism exists — re-authentication is always explicit.

                                ## Authorization Model

                                The system uses granular RBAC: users hold roles, roles carry permissions. \
                                All protected endpoints enforce permission checks server-side via Spring Security \
                                method-level authorization (`@PreAuthorize`). The required authority string must be \
                                present in the JWT `authorities` claim.
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Auth Module Team")
                                .email("security@example.com"))
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
                                        + "Include as `Authorization: Bearer <token>` on authenticated requests.")));
    }
}
