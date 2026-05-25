package com.cashcontrol.api;

import com.cashcontrol.api.controller.GlobalExceptionHandler;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ConflictException;
import com.cashcontrol.api.domain.exception.ForbiddenAccessException;
import com.cashcontrol.api.domain.exception.InvalidCredentialsException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @RestController
    @RequestMapping("/test")
    static class TestController {

        record TestDto(@NotBlank String name) {}

        @PostMapping("/dto")
        ResponseEntity<Void> acceptDto(@Valid @RequestBody TestDto dto) {
            return ResponseEntity.ok().build();
        }

        @GetMapping("/invalid-credentials")
        void throwInvalidCredentials() {
            throw new InvalidCredentialsException();
        }

        @GetMapping("/not-found")
        void throwNotFound() {
            throw new ResourceNotFoundException("Resource not found.");
        }

        @GetMapping("/conflict")
        void throwConflict() {
            throw new ConflictException("Role already exists.");
        }

        @GetMapping("/access-denied")
        void throwAccessDenied() {
            throw new AccessDeniedException("Access denied");
        }

        @GetMapping("/business-rule")
        void throwBusinessRule() {
            throw new BusinessRuleException("Cannot delete account with transactions.");
        }

        @GetMapping("/forbidden-access")
        void throwForbiddenAccess() {
            throw new ForbiddenAccessException();
        }

        @GetMapping("/error")
        void throwServerError() {
            throw new RuntimeException("Something went wrong");
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void invalidDto_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/test/dto")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.correlationId").value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void missingRequiredField_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/test/dto")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").exists())
                .andExpect(jsonPath("$.correlationId").value(not(emptyOrNullString())));
    }

    @Test
    void invalidCredentials_returns401WithGenericMessage() throws Exception {
        mockMvc.perform(get("/test/invalid-credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Invalid credentials."))
                .andExpect(jsonPath("$.correlationId").value(not(emptyOrNullString())));
    }

    @Test
    void invalidCredentials_messageDoesNotRevealUserExistence() throws Exception {
        mockMvc.perform(get("/test/invalid-credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid credentials."));
    }

    @Test
    void resourceNotFound_returns404() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").value(not(emptyOrNullString())));
    }

    @Test
    void conflict_returns409() throws Exception {
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                .andExpect(jsonPath("$.correlationId").value(not(emptyOrNullString())));
    }

    @Test
    void accessDenied_returns403WithGenericMessage() throws Exception {
        mockMvc.perform(get("/test/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied."))
                .andExpect(jsonPath("$.correlationId").value(not(emptyOrNullString())));
    }

    @Test
    void unhandledException_returns500WithoutStackTrace() throws Exception {
        mockMvc.perform(get("/test/error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.correlationId").value(not(emptyOrNullString())));
    }

    @Test
    void unhandledException_responseBodyContainsNoStackTrace() throws Exception {
        mockMvc.perform(get("/test/error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.cause").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    void allErrorResponses_containCorrelationId() throws Exception {
        mockMvc.perform(get("/test/invalid-credentials"))
                .andExpect(jsonPath("$.correlationId").value(not(emptyOrNullString())));

        mockMvc.perform(get("/test/not-found"))
                .andExpect(jsonPath("$.correlationId").value(not(emptyOrNullString())));

        mockMvc.perform(get("/test/conflict"))
                .andExpect(jsonPath("$.correlationId").value(not(emptyOrNullString())));

        mockMvc.perform(get("/test/error"))
                .andExpect(jsonPath("$.correlationId").value(not(emptyOrNullString())));
    }

    @Test
    void businessRuleViolation_returns422() throws Exception {
        mockMvc.perform(get("/test/business-rule"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.message").value("Cannot delete account with transactions."))
                .andExpect(jsonPath("$.correlationId").value(not(emptyOrNullString())));
    }

    @Test
    void forbiddenAccess_returns403WithGenericMessage() throws Exception {
        mockMvc.perform(get("/test/forbidden-access"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied."))
                .andExpect(jsonPath("$.correlationId").value(not(emptyOrNullString())));
    }

    @Test
    void methodNotAllowed_returns405() throws Exception {
        mockMvc.perform(post("/test/not-found")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.correlationId").value(not(emptyOrNullString())));
    }
}
