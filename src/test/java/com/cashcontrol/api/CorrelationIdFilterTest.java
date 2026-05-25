package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class CorrelationIdFilterTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void correlationIdHeaderIsPresentOnResponse() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/health")).andReturn();
        assertThat(result.getResponse().getHeader("X-Correlation-Id")).isNotNull();
    }

    @Test
    void correlationIdIsValidUuid() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/health")).andReturn();
        String header = result.getResponse().getHeader("X-Correlation-Id");
        assertThat(header).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void differentRequestsGetDifferentCorrelationIds() throws Exception {
        MvcResult result1 = mockMvc.perform(get("/actuator/health")).andReturn();
        MvcResult result2 = mockMvc.perform(get("/actuator/health")).andReturn();

        String id1 = result1.getResponse().getHeader("X-Correlation-Id");
        String id2 = result2.getResponse().getHeader("X-Correlation-Id");

        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void correlationIdAppearsInUnauthorizedResponseBody() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/admin/users")).andReturn();
        String correlationIdHeader = result.getResponse().getHeader("X-Correlation-Id");
        String body = result.getResponse().getContentAsString();

        assertThat(body).contains(correlationIdHeader);
    }
}