package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class CsrfProtectionTest {

    @Autowired private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void postRegister_withoutCsrfToken_isNotRejectedWith403() throws Exception {
        // CSRF is disabled for stateless JWT API — no CSRF token needed
        // A 201 or 400 is acceptable; 403 from CSRF would be wrong
        var result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"csrf-test@example.com\",\"password\":\"Str0ng!Pass123\",\"consentAccepted\":true}"))
                .andReturn();
        assertNotEquals(403, result.getResponse().getStatus());
    }

    @Test
    void postLogin_withoutCsrfToken_isNotRejectedWith403() throws Exception {
        var result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"csrf-test@example.com\",\"password\":\"Str0ng!Pass123\"}"))
                .andReturn();
        assertNotEquals(403, result.getResponse().getStatus());
    }

    @Test
    void postPasswordResetRequest_withoutCsrfToken_isNotRejectedWith403() throws Exception {
        var result = mockMvc.perform(post("/api/v1/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"csrf-test@example.com\"}"))
                .andReturn();
        assertNotEquals(403, result.getResponse().getStatus());
    }

    @Test
    void loginResponse_doesNotContainSetCookieHeader() throws Exception {
        // JWT-based auth — no session cookies should be set
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.com\",\"password\":\"Str0ng!Pass123\"}"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void registerResponse_doesNotContainSetCookieHeader() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"csrf-nocookie-" + System.nanoTime()
                                + "@example.com\",\"password\":\"Str0ng!Pass123\",\"consentAccepted\":true}"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }
}
