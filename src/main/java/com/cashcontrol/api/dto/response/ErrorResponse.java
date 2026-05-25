package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Schema(description = "Standard error response envelope returned on all non-2xx responses")
public record ErrorResponse(
        @Schema(description = "Machine-readable error code", example = "VALIDATION_ERROR") String errorCode,
        @Schema(description = "Human-readable error message", example = "Request validation failed.") String message,
        @Schema(description = "Correlation ID for distributed tracing", example = "550e8400-e29b-41d4-a716-446655440000") String correlationId,
        @Schema(description = "UTC timestamp of the error") Instant timestamp,
        @Schema(description = "Field-level validation errors; only present on HTTP 400 validation failures") Map<String, String> fieldErrors
) {
    public static ErrorResponse of(String errorCode, String message, UUID correlationId) {
        return new ErrorResponse(errorCode, message, correlationId.toString(), Instant.now(), null);
    }

    public static ErrorResponse withFieldErrors(
            String errorCode, String message, UUID correlationId, Map<String, String> fieldErrors) {
        return new ErrorResponse(errorCode, message, correlationId.toString(), Instant.now(), fieldErrors);
    }
}
