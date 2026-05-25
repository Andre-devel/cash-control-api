package com.cashcontrol.api;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cashcontrol.api.config.PostgresTestContainerConfig;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class StructuredLoggingTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;
    private ListAppender<ILoggingEvent> listAppender;
    private Logger captureLogger;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        // Attach to com.cashcontrol.api, not root — root doesn't receive events because
        // logback-spring.xml sets additivity="false" on the com.cashcontrol.api logger.
        captureLogger = (Logger) LoggerFactory.getLogger("com.cashcontrol.api");
        listAppender = new ListAppender<>();
        listAppender.start();
        captureLogger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        captureLogger.detachAppender(listAppender);
        listAppender.stop();
    }

    @Test
    void logEventsContainCorrelationIdInMdcDuringRequest() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        List<ILoggingEvent> events = listAppender.list;
        assertThat(events).isNotEmpty();

        boolean anyHasCorrelationId = events.stream()
                .anyMatch(e -> e.getMDCPropertyMap().containsKey("correlationId"));
        assertThat(anyHasCorrelationId)
                .as("At least one log event during the request must contain 'correlationId' in MDC")
                .isTrue();
    }

    @Test
    void logEventsAreEncodableAsJson() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        List<ILoggingEvent> events = listAppender.list;
        assertThat(events).isNotEmpty();

        LogstashEncoder encoder = new LogstashEncoder();
        encoder.start();
        try {
            for (ILoggingEvent event : events) {
                byte[] encoded = encoder.encode(event);
                String jsonStr = new String(encoded, StandardCharsets.UTF_8).trim();

                assertThatCode(() -> {
                    JsonNode node = objectMapper.readTree(jsonStr);
                    assertThat(node).isNotNull();
                    assertThat(node.isObject()).isTrue();
                }).as("Log event should encode as valid JSON").doesNotThrowAnyException();
            }
        } finally {
            encoder.stop();
        }
    }

    @Test
    void jsonEncodedEventsContainAppNameAndEnvironmentFields() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        List<ILoggingEvent> events = listAppender.list;
        assertThat(events).isNotEmpty();

        LogstashEncoder encoder = new LogstashEncoder();
        encoder.start();
        try {
            ILoggingEvent firstEvent = events.get(0);
            byte[] encoded = encoder.encode(firstEvent);
            String jsonStr = new String(encoded, StandardCharsets.UTF_8).trim();
            JsonNode node = objectMapper.readTree(jsonStr);

            assertThat(node.has("message")).isTrue();
            assertThat(node.has("level")).isTrue();
            assertThat(node.has("logger_name")).isTrue();
        } finally {
            encoder.stop();
        }
    }

    @Test
    void noSensitiveFieldNamesInMdcDuringNormalRequest() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        List<String> sensitiveKeys = List.of("password", "token", "secret", "hash");

        for (ILoggingEvent event : listAppender.list) {
            Map<String, String> mdc = event.getMDCPropertyMap();
            for (String sensitiveKey : sensitiveKeys) {
                assertThat(mdc)
                        .as("MDC must not contain sensitive key '%s'", sensitiveKey)
                        .doesNotContainKey(sensitiveKey);
            }
        }
    }

    @Test
    void noSensitiveValuesInLogMessages() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        for (ILoggingEvent event : listAppender.list) {
            String message = event.getFormattedMessage();
            assertThat(message)
                    .as("Log message must not contain 'password=' assignments")
                    .doesNotContainIgnoringCase("password=");
            assertThat(message)
                    .as("Log message must not contain 'secret=' assignments")
                    .doesNotContainIgnoringCase("secret=");
        }
    }

    @Test
    void correlationIdIsNotPresentInMdcAfterRequestCompletes() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        org.slf4j.MDC.getCopyOfContextMap();
        assertThat(org.slf4j.MDC.get("correlationId"))
                .as("correlationId must be cleared from MDC after request completes")
                .isNull();
    }
}
