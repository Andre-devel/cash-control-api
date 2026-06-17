package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class PaymentMethodControllerIT {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;
    private String token;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        UUID userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) " +
                "VALUES (?, " +
                "  (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), " +
                "  (SELECT id FROM auth_origins WHERE slug = 'LOCAL'), " +
                "  NOW() - INTERVAL '1 minute') " +
                "RETURNING id",
                UUID.class,
                "pm-ctrl-it-" + UUID.randomUUID() + "@example.com");

        token = jwtService.generateToken(userId, List.of(), Instant.now());
    }

    @Test
    void listPaymentMethods_returns200WithSevenRows() throws Exception {
        mockMvc.perform(get("/api/v1/payment-methods")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(7));
    }

    @Test
    void listPaymentMethods_returnsInFixedDisplayOrder() throws Exception {
        mockMvc.perform(get("/api/v1/payment-methods")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("CASH"))
                .andExpect(jsonPath("$[1].slug").value("PIX"))
                .andExpect(jsonPath("$[2].slug").value("DEBIT_CARD"))
                .andExpect(jsonPath("$[3].slug").value("CREDIT_CARD"))
                .andExpect(jsonPath("$[4].slug").value("BANK_TRANSFER"))
                .andExpect(jsonPath("$[5].slug").value("BOLETO"))
                .andExpect(jsonPath("$[6].slug").value("OTHER"));
    }

    @Test
    void listPaymentMethods_responseContainsIdSlugAndName() throws Exception {
        mockMvc.perform(get("/api/v1/payment-methods")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").isNotEmpty())
                .andExpect(jsonPath("$[0].slug").isNotEmpty())
                .andExpect(jsonPath("$[0].name").isNotEmpty());
    }

    @Test
    void listPaymentMethods_withoutJwt_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/payment-methods"))
                .andExpect(status().isUnauthorized());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String bearer() {
        return "Bearer " + token;
    }
}
